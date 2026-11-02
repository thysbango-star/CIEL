package dev.ciel.game

import dev.ciel.task.ActionPlanner
import dev.ciel.task.AgentContext
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.ConfirmationRequest
import dev.ciel.task.DeviceAction
import dev.ciel.task.PlannerDecision
import dev.ciel.task.Risk
import dev.ciel.task.ScreenAgent
import dev.ciel.task.ScreenSnapshot
import dev.ciel.task.StepEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** What the activity notch shows. Every field comes from the running engine — nothing is simulated. */
data class GameActivity(
    val taskId: String,
    val game: String,
    val title: String,
    val stepIndex: Int,
    val stepCount: Int,
    val stepTitle: String,
    val currentAction: String,
    val status: Status,
    val screen: String? = null,
    val needsAttention: Boolean = false,
    val log: List<String> = emptyList(),
    val finalReport: String? = null,
    /** Queue view: one line per task with its state. */
    val queue: List<QueueItem> = emptyList(),
    val nextAction: String? = null,
    val usage: UsageStats = UsageStats(),
    /** Last real tap sent to Android, as screen fractions, with its label (for the tap indicator). */
    val lastTap: TapMark? = null,
) {
    data class QueueItem(val label: String, val state: ItemState, val note: String? = null)
    enum class ItemState(val symbol: String) { DONE("✓"), RUNNING("●"), WAITING("○"), SKIPPED("–"), BLOCKED("!") }
    /** [exact]: the real touch point sent to Android; false: the targeted element's centre (Android clicked the element itself). */
    data class TapMark(val fx: Float, val fy: Float, val label: String, val exact: Boolean = true, val at: Long = System.currentTimeMillis())
    enum class Status(val label: String) { RUNNING("Running"), WAITING("Waiting for you"), PAUSED("Paused"), FINISHED("Finished"), FAILED("Stopped") }
    val progressText: String get() = if (stepCount > 0) "${minOf(stepIndex + 1, stepCount)} of $stepCount steps" else ""
}

/** A decision only the user can make. Only the action that depends on it waits. */
data class PendingQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val consequence: String,
    /** The element the question is about (approving it lets the action through). */
    val target: String?,
    val askedAt: Long = System.currentTimeMillis(),
)

data class ChatLine(val fromUser: Boolean, val text: String, val at: Long = System.currentTimeMillis())

/**
 * Non-blocking communication between the user and a running game task.
 *  - Informational questions are answered while the task keeps going.
 *  - Clarifications pause only the dependent action (the planner is told to do other work).
 *  - Approvals for money / premium currency block through the confirmation gate.
 *  - Directives ("skip that building") are read before every decision.
 */
class TaskChannel {
    private val _questions = MutableStateFlow<List<PendingQuestion>>(emptyList())
    val questions: StateFlow<List<PendingQuestion>> = _questions.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat.asStateFlow()
    private val directiveList = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val approved = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val declined = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val directives: List<String> get() = directiveList.toList()
    val approvedTargets: Set<String> get() = approved.toSet()
    fun isDeclined(target: String?) = target != null && target.lowercase() in declined

    /** Posts a question unless the same one is already waiting. Returns its id. */
    fun ask(question: String, options: List<String>, consequence: String, target: String?): String {
        _questions.value.firstOrNull { it.question == question }?.let { return it.id }
        val q = PendingQuestion(UUID.randomUUID().toString(), question, options, consequence, target)
        _questions.update { it + q }
        say(false, "$question ($consequence)")
        return q.id
    }

    /** The user picked [option] (index 0 = proceed, anything else = don't). */
    fun answer(id: String, option: Int) {
        val q = _questions.value.firstOrNull { it.id == id } ?: return
        _questions.update { list -> list.filter { it.id != id } }
        val proceed = option == 0
        val chosen = q.options.getOrNull(option) ?: if (proceed) "Yes" else "No"
        say(true, chosen)
        val target = q.target?.lowercase()
        if (target != null) { if (proceed) approved += target else declined += target }
        directiveList += if (proceed) "The user approved: ${q.question} → $chosen. You may do it now." else "The user declined: ${q.question} → $chosen. Do not do it; continue with the rest."
    }

    fun directive(text: String) { directiveList += text; say(true, text) }
    fun say(fromUser: Boolean, text: String) { _chat.update { (it + ChatLine(fromUser, text.take(600))).takeLast(40) } }
    fun clearQuestions() = _questions.update { emptyList() }
}

/** What the user's message to a running task means. */
sealed class TaskMessage {
    data class Question(val text: String) : TaskMessage()
    object Pause : TaskMessage()
    object Resume : TaskMessage()
    object Stop : TaskMessage()
    data class Skip(val what: String) : TaskMessage()
    data class Instruction(val text: String) : TaskMessage()

    companion object {
        fun parse(raw: String): TaskMessage {
            val t = raw.trim().lowercase().removePrefix("ciel").trim(' ', ',', '.', '!')
            return when {
                t.matches(Regex("(emergency )?stop( everything| the task| playing)?|cancel( the task)?|abort")) -> Stop
                t.matches(Regex("pause( the task)?|hold on|wait")) -> Pause
                t.matches(Regex("(continue|resume|go on|carry on|keep going)( the task)?")) -> Resume
                t.startsWith("skip") -> Skip(raw.trim().substringAfter(' ', "that").trim(' ', '.', '!'))
                t.startsWith("don't ") || t.startsWith("do not ") || t.startsWith("only ") || t.startsWith("instead") || t.startsWith("focus on") || t.startsWith("prioriti") ->
                    Instruction(raw.trim())
                else -> Question(raw.trim())
            }
        }
    }
}

/** Answers questions about a running task using its live context (an AI model on Android). */
fun interface TaskQuestionAnswerer {
    suspend fun answer(context: String, question: String): String?
}

/**
 * Wraps the AI planner for game tasks. Before each decision it reads the screen (with several
 * observations combined), handles loading / errors / blockers without asking the model, and
 * after each proposed action applies the user's spending rules:
 *   allow → act · clarify → ask the user without stopping, steer the model to other work ·
 *   approve → explicit confirmation · block → steer the model away.
 */
class GamePlannerDecorator(
    private val inner: ActionPlanner,
    private val session: GameSessionState,
    private val readOnly: () -> Boolean,
) : ActionPlanner {
    private var loadingWaits = 0
    private suspend fun propose(c: AgentContext, r: ScreenReading, attempt: Int) = proposeImpl(inner, session, c, r, attempt)
    private var retriedNetwork = false

    override suspend fun next(context: AgentContext): PlannerDecision {
        if (session.skipTask) return session.skip("Skipped by you.")
        val reading = session.observe(context.screen)
        when (reading.state) {
            GameScreenState.LOADING -> {
                if (++loadingWaits <= 12) { session.status("Waiting for the game to load"); return PlannerDecision.Act(DeviceAction.Wait(2000), "The game is loading.", "the game finishes loading") }
                return session.blocker("The game kept loading for over 20 seconds.")
            }
            GameScreenState.HUMAN_CHECK -> return session.blocker("A human-verification check is on screen. Ciel never solves these — please complete it yourself, then run the task again.")
            GameScreenState.MAINTENANCE -> if (reading.confidence != Confidence.LOW) return session.blocker("The game is under maintenance.")
            GameScreenState.UPDATE_REQUIRED -> if (reading.confidence != Confidence.LOW) return session.blocker("The game asks to be updated. Update it in the Play Store; Ciel's knowledge will be re-checked afterwards.")
            GameScreenState.LOGIN -> if (reading.confidence != Confidence.LOW) return session.blocker("The game is asking you to log in. Ciel never handles game logins or passwords.")
            GameScreenState.NETWORK_ERROR -> {
                val retry = reading.elements.firstOrNull { it.label.contains("retry", true) || it.label.equals("ok", true) }
                if (!retriedNetwork && retry?.nodeId != null) {
                    retriedNetwork = true; session.status("Connection problem — tapping \"${retry.label}\" once")
                    return PlannerDecision.Act(DeviceAction.ClickNode(retry.nodeId, retry.label), "Recover from a connection error.", "the game reconnects")
                }
                if (reading.confidence != Confidence.LOW) return session.blocker("The game lost its connection.")
            }
            else -> loadingWaits = 0
        }
        // Blind: a canvas game with neither text nor a screenshot cannot be understood safely.
        if (!reading.textAvailable && context.screen.screenshotBase64 == null) {
            return session.blocker("Ciel cannot see this game: it draws everything itself and screenshots are off or unavailable. Turn on \"Let Ciel read the screen\" in Settings → Games (Android 11+), then try again.")
        }

        var warnings = context.warnings + "Ciel's reading of this screen: ${reading.summary()}." + session.pendingNotes()
        if (readOnly()) warnings = warnings + "This step is READ-ONLY: open, read and go back only."
        repeat(3) { attempt ->
            val c = context.copy(warnings = warnings, directives = context.directives)
            val decision = propose(c, reading, attempt) ?: return session.blocker(
                "No AI model is available and Ciel doesn't know how to handle this screen yet (${reading.state.label}). Connect a model (Ollama works offline on your network) or run Explore & learn while online.")
            if (decision !is PlannerDecision.Act) return decision
            val target = ScreenAgent.targetLabel(decision.action, context.screen)
            var cls = session.gate.classify(decision.action, decision.rationale, context.screen, reading)
            if (session.isForbiddenArea(target)) return session.skip("Competitive PvP (\"$target\") is not played by Ciel.")
            if (readOnly() && cls != GameActionClass.SAFE) {
                warnings = warnings + "Not allowed in this step: \"${target ?: decision.action.describe()}\" would ${cls.name.lowercase().replace('_', ' ')}. Choose a read-only action."
                return@repeat
            }
            if (session.channel.isDeclined(target)) {
                warnings = warnings + "The user declined \"$target\". Do not tap it; continue with other parts of the objective."
                return@repeat
            }
            when (val gate = session.gate.decide(cls, session.policy, target, session.channel.approvedTargets)) {
                GateDecision.Allow -> { session.status(decision.action, target); session.tapped(decision.action, context.screen, target); return decision }
                is GateDecision.Block -> warnings = warnings + "Not allowed: ${gate.reason} Choose a different action, or finish/fail this step."
                is GateDecision.Clarify -> {
                    session.channel.ask(gate.question, gate.options, gate.consequence, target)
                    session.waiting(true)
                    warnings = warnings + "The user is being asked: \"${gate.question}\". Do NOT do that now. Continue with other parts of this step; if nothing else is possible, reply with wait."
                    if (attempt == 2) return PlannerDecision.Act(DeviceAction.Wait(3000), "Waiting for the user's decision on \"${target ?: "this"}\".")
                }
                is GateDecision.Approve -> {
                    session.waiting(true)
                    val ok = session.confirm(ConfirmationRequest(gate.title, gate.detail, Risk.CONSEQUENTIAL, decision.action.describe()))
                    session.waiting(false)
                    if (ok) { target?.let { session.channel.answerApproved(it) }; session.tapped(decision.action, context.screen, target); return decision }
                    warnings = warnings + "The user declined: ${gate.title}. Do not do it."
                }
            }
        }
        return PlannerDecision.Act(DeviceAction.Wait(1500), "No permitted action found; re-checking the screen.")
    }
}

/**
 * Local first: routine decisions are made on the phone; the AI is asked only when the local rules
 * cannot decide. If the AI is unreachable (credits, limits, offline), the session switches to
 * local-only for the rest of the task and tells the user, instead of giving up.
 */
private suspend fun GamePlannerDecorator.proposeImpl(inner: ActionPlanner, session: GameSessionState, c: AgentContext, reading: ScreenReading, attempt: Int): PlannerDecision? {
    if (attempt == 0) session.local.decide(c, reading)?.let { session.count(local = true); return it }
    if (!session.stats.aiAvailable) return session.local.offline(c, reading)?.also { session.count(local = true) }
    session.count(local = false)
    val d = inner.next(c)
    if (d is PlannerDecision.Fail && (d.reason.startsWith("The AI providers could not be reached") || d.reason.startsWith("No AI provider configured"))) {
        session.aiUnavailable(d.reason)
        return session.local.offline(c, reading)?.also { session.count(local = true) }
    }
    return d
}

private fun TaskChannel.answerApproved(target: String) { directive("Approved \"$target\".") }

/**
 * Shared state of one game task: knowledge (updated as Ciel learns), live activity for the notch,
 * the question channel, the spending policy and the action log with verification.
 */
class GameSessionState(
    val game: String,
    val gameVersion: String?,
    var knowledge: GameKnowledgeBase,
    val policy: SpendingPolicy,
    val vocab: GameVocabulary,
    val channel: TaskChannel,
    private val confirmGate: ConfirmationGate,
    private val activityFlow: MutableStateFlow<GameActivity?>,
    private val forbiddenAreas: List<String>,
) {
    val reader = GameScreenReader(vocab)
    val gate = GameActionGate(vocab)
    private val tracker = ScreenStateTracker()
    var lastReading: ScreenReading? = null; private set
    var blockerReason: String? = null; private set
    var skipReason: String? = null
    /** Set by "Skip task": the current task ends at the next decision. */
    @Volatile var skipTask = false
    var currentStep: GamePlanStep? = null
    val local = LocalGamePlanner(this)
    var stats = UsageStats(); private set
    /** Events read from the game's screens during this session. */
    val events = mutableListOf<EventInfo>()

    fun count(local: Boolean) {
        stats = if (local) stats.copy(localDecisions = stats.localDecisions + 1) else stats.copy(aiCalls = stats.aiCalls + 1)
        activityFlow.update { it?.copy(usage = stats) }
    }
    fun aiUnavailable(reason: String) {
        if (!stats.aiAvailable) return
        stats = stats.copy(aiAvailable = false, aiFailures = stats.aiFailures + 1)
        activityFlow.update { it?.copy(usage = stats) }
        channel.say(false, "My AI model isn't available (${reason.take(120)}). I'm continuing with what I can do on the phone.")
        addAttention("AI became unavailable during the task; continued locally where possible.")
    }
    fun tapped(action: DeviceAction, screen: ScreenSnapshot, target: String?) {
        var exact = true
        val p = when (action) {
            is DeviceAction.TapRel -> action.fx to action.fy
            is DeviceAction.TapAt -> if (screen.screenWidth > 0) action.x.toFloat() / screen.screenWidth to action.y.toFloat() / screen.screenHeight else null
            is DeviceAction.ClickNode -> screen.nodes.firstOrNull { it.id == action.nodeId }?.takeIf { screen.screenWidth > 0 && !it.bounds.isEmpty }
                ?.also { exact = it.className == dev.ciel.task.OCR_CLASS }
                ?.let { it.bounds.centerX.toFloat() / screen.screenWidth to it.bounds.centerY.toFloat() / screen.screenHeight }
            else -> null
        } ?: return
        activityFlow.update { it?.copy(lastTap = GameActivity.TapMark(p.first, p.second, target ?: action.describe(), exact)) }
    }
    val attention = mutableListOf<String>()
    val log = mutableListOf<String>()
    private var learned = 0

    fun observe(screen: ScreenSnapshot): ScreenReading = tracker.add(reader.read(screen)).also { r ->
        lastReading = r
        activityFlow.update { it?.copy(screen = r.state.label) }
        r.timers.take(2).forEach { t -> if (r.state != GameScreenState.EVENT) addAttention("A timer is running on ${r.state.label.lowercase()}: $t (come back later)") }
        if (r.state == GameScreenState.EVENT) captureEvent(screen, r)
    }

    /** Records an event's name and the remaining time exactly as the game shows it (never invented). */
    private fun captureEvent(screen: ScreenSnapshot, r: ScreenReading) {
        val timer = r.timers.firstOrNull() ?: return
        val remaining = EventDeadlines.parseRemaining(timer) ?: return
        val name = screen.nodes.filter { n -> val t = n.text ?: return@filter false
            t.length in 4..40 && t.any(Char::isLetter) && EventDeadlines.parseRemaining(t) == null && !GameScreenReader.containsWord(t.lowercase(), "claim") }
            .minByOrNull { it.bounds.top }?.text?.trim() ?: "Event"
        val attempts = r.counters.firstOrNull()
        val e = EventInfo(name, System.currentTimeMillis() + remaining, timer, dailyAttempts = attempts, claimable = r.has(ElementKind.CLAIM))
        events.removeAll { it.name.equals(name, true) }
        events += e
    }

    fun status(text: String) = activityFlow.update { it?.copy(currentAction = text, status = if (it.status == GameActivity.Status.WAITING) it.status else GameActivity.Status.RUNNING) }
    fun status(action: DeviceAction, target: String?) = status(target?.let { "Tapping \"$it\"" } ?: action.describe())
    fun next(text: String?) = activityFlow.update { it?.copy(nextAction = text) }
    fun queue(items: List<GameActivity.QueueItem>) = activityFlow.update { it?.copy(queue = items) }
    fun waiting(on: Boolean) = activityFlow.update { it?.copy(status = if (on) GameActivity.Status.WAITING else GameActivity.Status.RUNNING, needsAttention = on) }
    fun pendingNotes(): List<String> = channel.questions.value.map { "Waiting for the user's answer: ${it.question} — do not do that yet." }
    fun isForbiddenArea(target: String?) = target != null && forbiddenAreas.any { GameScreenReader.containsWord(target.lowercase(), it) }
    suspend fun confirm(r: ConfirmationRequest) = confirmGate.confirm(r)

    fun blocker(reason: String): PlannerDecision { blockerReason = reason; addAttention(reason); return PlannerDecision.Fail(reason) }
    fun skip(reason: String): PlannerDecision { skipReason = reason; return PlannerDecision.Fail(reason) }
    fun addAttention(s: String) { if (s !in attention && attention.size < 12) attention += s }

    /**
     * Learning from one executed action (Part 12): semantic route (which labelled element led
     * from which screen to which), what each screen offers, contradictions when a known route
     * did nothing, and a verification line for the log.
     */
    fun learn(e: StepEvent) {
        val before = reader.read(e.before)
        val after = reader.read(e.after)
        val verdict = when {
            !e.changed -> "no visible change"
            e.expected != null -> "screen changed (expected: ${e.expected})"
            else -> "screen changed"
        }
        log += "${e.target?.let { "Tap \"$it\"" } ?: e.action.describe()} on ${before.state.label} → ${after.state.label}; $verdict"
        if (log.size > 60) log.removeAt(0)
        activityFlow.update { it?.copy(log = log.takeLast(6)) }
        val hint = (e.action as? DeviceAction.TapRel)?.let { it.fx to it.fy }
            ?: e.action.let { a -> if (a is DeviceAction.TapAt && e.before.screenWidth > 0) a.x.toFloat() / e.before.screenWidth to a.y.toFloat() / e.before.screenHeight else null }
        val target = e.target
        if (e.changed && target != null && after.state != before.state && after.state != GameScreenState.UNKNOWN && !after.state.blocking && after.confidence != Confidence.LOW) {
            knowledge = knowledge.record(KnowledgeEntry(
                system = systemOf(after.state), kind = KnowledgeKind.LOCATION, title = "Open ${after.state.label.lowercase()}",
                detail = "Reached by tapping \"$target\" from the ${before.state.label.lowercase()}.",
                howToReach = listOf("On ${before.state.label.lowercase()}: tap \"$target\""),
                landmarks = after.evidence.take(6), positionHint = hint, confidence = Confidence.MEDIUM, gameVersion = gameVersion,
            ))
            learned++
        }
        if (!e.changed && target != null) {
            knowledge.entries.filter { k -> k.howToReach.any { it.contains("\"$target\"") } }.forEach { knowledge = knowledge.contradict(it.key) }
        }
        if (after.has(ElementKind.CLAIM) && after.state != GameScreenState.UNKNOWN) {
            knowledge = knowledge.record(KnowledgeEntry(systemOf(after.state), KnowledgeKind.ACTION, "Claim rewards",
                "Claim buttons appear on the ${after.state.label.lowercase()}: ${after.elements.filter { it.kind == ElementKind.CLAIM }.map { it.label }.distinct().take(3).joinToString()}.",
                landmarks = after.evidence.take(4), confidence = Confidence.MEDIUM, gameVersion = gameVersion))
        }
        if (after.has(ElementKind.UPGRADE)) {
            knowledge = knowledge.record(KnowledgeEntry(systemOf(after.state), KnowledgeKind.RISK, "Upgrades spend resources",
                "Upgrade options seen on the ${after.state.label.lowercase()}; they follow your spending rules.", confidence = Confidence.MEDIUM, gameVersion = gameVersion))
        }
    }

    fun recordStep(step: GamePlanStep, success: Boolean, summary: String, targets: List<String>) {
        if (!success) return
        val kind = if (step.kind == StepKind.EXPLORE || step.kind == StepKind.INSPECT) KnowledgeKind.MECHANIC else KnowledgeKind.WORKFLOW
        knowledge = knowledge.record(KnowledgeEntry(step.system, kind,
            if (kind == KnowledgeKind.MECHANIC) "${step.system}: what Ciel found" else "How to: ${step.title.lowercase()}",
            summary.take(400), howToReach = targets.distinct().takeLast(8).map { "tap \"$it\"" }, confidence = Confidence.MEDIUM, gameVersion = gameVersion))
    }

    companion object {
        fun systemOf(s: GameScreenState) = when (s) {
            GameScreenState.QUESTS -> "Quests"; GameScreenState.PRODUCTION -> "Production"
            GameScreenState.BUILDING, GameScreenState.UPGRADE -> "Buildings"; GameScreenState.REWARD -> "Rewards and mail"
            GameScreenState.BATTLE_PREP, GameScreenState.BATTLE_RESULT -> "Battles"; GameScreenState.EVENT -> "Events"
            GameScreenState.SHOP -> "Shop"; GameScreenState.INVENTORY -> "Inventory"; GameScreenState.CHARACTERS -> "Characters"
            GameScreenState.MAIN -> "Main"; else -> "General"
        }
    }
}
