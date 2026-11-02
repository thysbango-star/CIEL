package dev.ciel.game

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.json
import dev.ciel.skills.Capability
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.builtin.SystemSkill
import dev.ciel.skills.reqStr
import dev.ciel.skills.str
import dev.ciel.task.AgentOptions
import dev.ciel.task.AgentRun
import dev.ciel.task.DeviceAction
import dev.ciel.task.ScreenAgent
import dev.ciel.task.ConfirmationGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class GameCategory { RPG, STRATEGY, SIMULATION, ADVENTURE, CASUAL, PUZZLE, MANAGEMENT, OTHER }

/**
 * Whether automation is allowed for this game. Defaults to UNREVIEWED: the user must confirm
 * that the game's terms permit automation before autonomous play is enabled.
 */
enum class AutomationPolicy { UNREVIEWED, ALLOWED_SINGLE_PLAYER, MENUS_ONLY, PROHIBITED }

data class VerifiedStrategy(
    val objective: String,
    /** Human-readable step descriptions that led to a verified completion. */
    val steps: List<String>,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val gameVersion: String?,
    val lastUsed: Long = System.currentTimeMillis(),
)

data class GameProfile(
    val packageName: String,
    val name: String,
    val category: GameCategory = GameCategory.OTHER,
    val policy: AutomationPolicy = AutomationPolicy.UNREVIEWED,
    val versionName: String? = null,
    /** Known screens: signature -> description ("main lobby", "daily rewards popup"). */
    val knownScreens: Map<String, String> = emptyMap(),
    val navigationNotes: List<String> = emptyList(),
    val mechanics: List<String> = emptyList(),
    val questStructure: List<String> = emptyList(),
    val strategies: List<VerifiedStrategy> = emptyList(),
    val supportedActions: List<String> = listOf("tap", "swipe", "navigate menus", "collect rewards"),
    val limitations: List<String> = emptyList(),
    /** Set when the installed version differs from the version the knowledge was learned on. */
    val interfaceChangeSuspected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Learned, searchable knowledge with confidence (Game Intelligence). */
    val knowledge: GameKnowledgeBase = GameKnowledgeBase(),
    /** The user's rules for spending in-game resources. */
    val spending: SpendingPolicy = SpendingPolicy(),
    /** Gameplay-manager queue, presets, and events read from the game. */
    val queue: GameQueue = GameQueue(),
    val presets: List<QueuePreset> = emptyList(),
    val events: List<EventInfo> = emptyList(),
) {
    fun knowledgeSummary(): String = buildString {
        append("Game: $name ($packageName), category ${category.name.lowercase()}, version ${versionName ?: "unknown"}.\n")
        if (interfaceChangeSuspected) append("WARNING: the game was updated since this knowledge was recorded; verify every step.\n")
        if (navigationNotes.isNotEmpty()) append("Navigation: ${navigationNotes.joinToString("; ")}\n")
        if (mechanics.isNotEmpty()) append("Mechanics: ${mechanics.joinToString("; ")}\n")
        if (questStructure.isNotEmpty()) append("Quests: ${questStructure.joinToString("; ")}\n")
        if (limitations.isNotEmpty()) append("Limitations: ${limitations.joinToString("; ")}\n")
        strategies.sortedByDescending { it.successCount - it.failureCount }.take(5).forEach {
            append("Verified strategy for \"${it.objective}\" (${it.successCount} ok / ${it.failureCount} failed): ${it.steps.joinToString(" → ")}\n")
        }
        if (knownScreens.isNotEmpty()) append("Known screens: ${knownScreens.values.distinct().take(15).joinToString()}\n")
    }
}

interface GameProfileRepository {
    suspend fun get(packageName: String): GameProfile?
    suspend fun save(profile: GameProfile)
    suspend fun all(): List<GameProfile>
    suspend fun delete(packageName: String)
}

class InMemoryGameProfileRepository : GameProfileRepository {
    private val map = java.util.concurrent.ConcurrentHashMap<String, GameProfile>()
    override suspend fun get(packageName: String) = map[packageName]
    override suspend fun save(profile: GameProfile) { map[profile.packageName] = profile }
    override suspend fun all() = map.values.toList()
    override suspend fun delete(packageName: String) { map.remove(packageName) }
}

/** Platform info about installed packages (PackageManager on Android). */
interface InstalledPackageInfo {
    fun isGame(packageName: String): Boolean
    fun versionName(packageName: String): String?
    fun label(packageName: String): String?
}

/** Profiles serialise to JSON so they can be exported, shared and re-imported. */
object GameProfileCodec {
    fun encode(p: GameProfile): String = json {
        "packageName" to p.packageName; "name" to p.name; "category" to p.category.name; "policy" to p.policy.name
        opt("versionName", p.versionName)
        "knownScreens" to p.knownScreens; "navigationNotes" to p.navigationNotes; "mechanics" to p.mechanics
        "questStructure" to p.questStructure; "supportedActions" to p.supportedActions; "limitations" to p.limitations
        "interfaceChangeSuspected" to p.interfaceChangeSuspected; "updatedAt" to p.updatedAt
        "knowledge" to p.knowledge.entries.map { GameKnowledgeBase.encode(it) }
        "spending" to SpendingPolicy.encode(p.spending)
        "queue" to QueuePlanner.encode(p.queue)
        "presets" to p.presets.map { pr -> json { "name" to pr.name; "queue" to QueuePlanner.encode(pr.queue) } }
        "events" to p.events.map { QueuePlanner.encodeEvent(it) }
        "strategies" to p.strategies.map { s ->
            json { "objective" to s.objective; "steps" to s.steps; "successCount" to s.successCount; "failureCount" to s.failureCount; opt("gameVersion", s.gameVersion); "lastUsed" to s.lastUsed }
        }
    }.toString()

    /**
     * [trustPolicy] is true only for the app's own database. Imported/shared profiles never
     * carry an "allowed" automation decision: the user must review the game locally.
     */
    fun decode(text: String, trustPolicy: Boolean = false): GameProfile {
        val o = Json.parse(text)
        fun list(k: String) = o[k]?.array?.mapNotNull { it.string } ?: emptyList()
        return GameProfile(
            packageName = o["packageName"]?.string ?: throw IllegalArgumentException("packageName missing"),
            name = o["name"]?.string ?: "Game",
            category = runCatching { GameCategory.valueOf(o["category"]?.string ?: "OTHER") }.getOrDefault(GameCategory.OTHER),
            policy = when {
                trustPolicy -> runCatching { AutomationPolicy.valueOf(o["policy"]?.string ?: "") }.getOrDefault(AutomationPolicy.UNREVIEWED)
                o["policy"]?.string == AutomationPolicy.PROHIBITED.name -> AutomationPolicy.PROHIBITED
                else -> AutomationPolicy.UNREVIEWED
            },
            versionName = o["versionName"]?.string,
            knownScreens = o["knownScreens"]?.obj?.fields?.mapValues { it.value.string ?: "" } ?: emptyMap(),
            navigationNotes = list("navigationNotes"), mechanics = list("mechanics"), questStructure = list("questStructure"),
            supportedActions = list("supportedActions"), limitations = list("limitations"),
            interfaceChangeSuspected = o["interfaceChangeSuspected"]?.bool ?: false,
            updatedAt = o["updatedAt"]?.long ?: System.currentTimeMillis(),
            knowledge = GameKnowledgeBase(o["knowledge"]?.array?.mapNotNull { GameKnowledgeBase.decode(it, imported = !trustPolicy) } ?: emptyList()),
            // Spending rules are personal: shared profiles never carry them.
            spending = if (trustPolicy) SpendingPolicy.decode(o["spending"]) else SpendingPolicy(),
            queue = if (trustPolicy) QueuePlanner.decode(o["queue"]) else GameQueue(),
            presets = if (trustPolicy) o["presets"]?.array?.mapNotNull { pr -> pr["name"]?.string?.let { QueuePreset(it, QueuePlanner.decode(pr["queue"])) } } ?: emptyList() else emptyList(),
            events = o["events"]?.array?.map { QueuePlanner.decodeEvent(it) } ?: emptyList(),
            strategies = o["strategies"]?.array?.map { s ->
                VerifiedStrategy(s["objective"]?.string ?: "", s["steps"]?.array?.mapNotNull { it.string } ?: emptyList(),
                    s["successCount"]?.int ?: 0, s["failureCount"]?.int ?: 0, s["gameVersion"]?.string, s["lastUsed"]?.long ?: 0)
            } ?: emptyList(),
        )
    }
}

/**
 * Game recognition, profile maintenance, learning, and autonomous play through the
 * shared ScreenAgent (so all safety guarantees — stop, purchase confirmation, no
 * human-verification solving — apply to games too).
 */
class GameEngine(
    private val profiles: GameProfileRepository,
    private val packages: InstalledPackageInfo,
    private val agentFactory: () -> ScreenAgent?,
    /** Whether screenshots may be sent to the vision model (user setting). */
    private val screenshotsAllowed: () -> Boolean = { true },
    /** Explicit approvals (premium currency, real money). Defaults to "no". */
    private val confirmations: ConfirmationGate = ConfirmationGate { false },
    /** Answers questions about a running task (AI model); null → answers from local state. */
    var answerer: TaskQuestionAnswerer? = null,
) {
    private val _activity = MutableStateFlow<GameActivity?>(null)
    /** Live status for the activity notch: comes only from the running engine. */
    val activity: StateFlow<GameActivity?> = _activity.asStateFlow()
    private val channels = java.util.concurrent.ConcurrentHashMap<String, TaskChannel>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, GameSessionState>()
    fun channel(taskId: String): TaskChannel? = channels[taskId]
    /** The task the notch is showing, if any. */
    fun currentChannel(): TaskChannel? = _activity.value?.let { channels[it.taskId] }
    fun dismissActivity() { _activity.value = _activity.value?.takeIf { it.status == GameActivity.Status.RUNNING || it.status == GameActivity.Status.WAITING || it.status == GameActivity.Status.PAUSED } }
    fun markPaused(paused: Boolean) = _activity.update { it?.copy(status = if (paused) GameActivity.Status.PAUSED else GameActivity.Status.RUNNING) }

    /** Loads or creates a profile and flags interface changes after game updates. */
    suspend fun profileFor(packageName: String): GameProfile {
        val installedVersion = packages.versionName(packageName)
        val existing = profiles.get(packageName)
        if (existing == null) {
            val p = GameCatalog.seed(GameProfile(packageName, packages.label(packageName) ?: packageName, versionName = installedVersion))
            profiles.save(p)
            return p
        }
        if (installedVersion != null && existing.versionName != null && installedVersion != existing.versionName) {
            // Never assume an element is still where it was: everything learned gets re-checked.
            val updated = existing.copy(versionName = installedVersion, interfaceChangeSuspected = true, updatedAt = System.currentTimeMillis(),
                knowledge = existing.knowledge.onGameUpdated(installedVersion))
            profiles.save(updated)
            return updated
        }
        return existing
    }

    data class PlayOutcome(val run: AgentRun?, val message: String)

    suspend fun play(packageName: String, objective: String, handle: dev.ciel.task.TaskHandle, maxSteps: Int = 60): PlayOutcome {
        val profile = profileFor(packageName)
        when (profile.policy) {
            AutomationPolicy.UNREVIEWED -> return PlayOutcome(null,
                "${profile.name} has not been reviewed for automation. Open Ciel → Games, confirm the game's terms allow automation for single-player play, then try again.")
            AutomationPolicy.PROHIBITED -> return PlayOutcome(null, "Automation is marked as prohibited for ${profile.name}.")
            else -> Unit
        }
        val agent = agentFactory() ?: return PlayOutcome(null, "Autonomous play needs the accessibility service and a configured AI provider.")
        val scope = if (profile.policy == AutomationPolicy.MENUS_ONLY) "\nRestriction: only navigate menus and collect rewards; do not play matches or levels." else ""
        val knowledge = profile.knowledgeSummary() + scope +
            "\nRules: never buy anything or spend premium currency without the user's confirmation; never play competitive PvP matches; stop if a human-verification check appears."
        handle.plan(listOf("Open ${profile.name}", "Analyse current screen", "Execute plan", "Verify objective", "Record what worked"), objective)
        val run = agent.run(handle, "In ${profile.name}: $objective", AgentOptions(maxSteps = maxSteps, includeScreenshots = screenshotsAllowed(), settleDelayMs = 1500, knowledge = knowledge))
        learn(profile, objective, run)
        return PlayOutcome(run, run.result.report)
    }

    /** Everything one game task needs: profile, session state, the decorated agent and rules. */
    private inner class Run(val profile: GameProfile, val session: GameSessionState, val agent: ScreenAgent, val rules: String, val channel: TaskChannel) {
        var readOnly = false
    }

    private suspend fun prepare(packageName: String, handle: dev.ciel.task.TaskHandle, title: String): Pair<Run?, String?> {
        val profile = profileFor(packageName)
        when (profile.policy) {
            AutomationPolicy.UNREVIEWED -> return null to
                "${profile.name} has not been reviewed for automation. Open Ciel → Settings → Games, confirm the game's terms allow automation for your single-player play, then try again."
            AutomationPolicy.PROHIBITED -> return null to "Automation is marked as prohibited for ${profile.name}."
            else -> Unit
        }
        val base = agentFactory() ?: return null to "Game tasks need Ciel's accessibility service (Settings → Accessibility → Ciel)."
        val entry = GameCatalog.entryFor(packageName)
        val channel = TaskChannel()
        channels[handle.id] = channel
        val session = GameSessionState(profile.name, profile.versionName, profile.knowledge, profile.spending,
            entry?.vocabulary ?: GameVocabulary.GENERIC, channel, confirmations, _activity, entry?.forbiddenAreas ?: GameCatalog.PVP)
        sessions[handle.id] = session
        val rules = "Rules: never buy anything or spend premium currency without the user's explicit approval; never play competitive PvP; " +
            "stop at human-verification checks; never type passwords. ${profile.spending.describe()}" +
            if (profile.policy == AutomationPolicy.MENUS_ONLY) " Restriction: menus and rewards only; do not play levels or battles." else ""
        lateinit var run: Run
        val agent = base.withPlanner { inner -> GamePlannerDecorator(inner, session) { run.readOnly } }
        run = Run(profile, session, agent, rules, channel)
        _activity.value = GameActivity(handle.id, profile.name, title, 0, 0, "", "Starting", GameActivity.Status.RUNNING)
        return run to null
    }

    /** Runs one plan step with the screen agent; local-first decisions, learning, live status. */
    private suspend fun runOneStep(r: Run, runStep: suspend (String, dev.ciel.task.AgentOptions) -> dev.ciel.task.AgentRun,
                                   step: GamePlanStep, budget: Int, context: String): StepResult {
        r.readOnly = step.readOnly
        r.session.skipReason = null
        r.session.currentStep = step
        r.session.local.reset()
        val targets = mutableListOf<String>()
        val run = runStep("In ${r.profile.name}: ${step.objective}", dev.ciel.task.AgentOptions(
            maxSteps = budget, includeScreenshots = screenshotsAllowed(), settleDelayMs = 1500,
            knowledge = "Game: ${r.profile.name} (version ${r.profile.versionName ?: "unknown"}). $context\n${r.rules}\n" +
                "What Ciel has learned (verify on screen before relying on it):\n" + r.session.knowledge.briefing(step.system + " " + step.title),
            directives = { r.channel.directives.takeLast(8) },
            onObserve = { _activity.update { a -> a?.copy(currentAction = if (a.status == GameActivity.Status.WAITING) a.currentAction else "Reading the screen") } },
            onStep = { e: dev.ciel.task.StepEvent ->
                r.session.learn(e)
                val t = e.target
                if (e.changed && t != null) targets.add(t)
            },
        ))
        val ok = run.result.success && run.result.verified
        r.session.recordStep(step, ok, run.result.report, targets)
        learn(r.profile.copy(knowledge = r.session.knowledge), step.title, run)
        return StepResult(step, ok, run.result.verified, run.result.report, r.session.skipReason)
    }

    private suspend fun finish(packageName: String, r: Run, handle: dev.ciel.task.TaskHandle, extra: (GameProfile) -> GameProfile = { it }) = withContext(NonCancellable) {
        // Knowledge (and events) are kept even if the task is stopped part-way.
        val latest = profiles.get(packageName) ?: r.profile
        val events = (latest.events.filter { e -> r.session.events.none { it.name.equals(e.name, true) } } + r.session.events)
            .filter { it.endsAt == null || it.endsAt > System.currentTimeMillis() - 86_400_000L }.takeLast(20)
        profiles.save(extra(latest.copy(knowledge = r.session.knowledge, events = events, updatedAt = System.currentTimeMillis())))
        r.channel.clearQuestions()
        sessions.remove(handle.id)
    }

    /**
     * Game Intelligence run: objective → prioritised plan → each step executed by the screen agent
     * through [GamePlannerDecorator] (local-first decisions, screen understanding, recovery,
     * spending rules, non-blocking questions) → learning from every action → honest report.
     */
    suspend fun runObjective(packageName: String, objective: String, handle: dev.ciel.task.TaskHandle, maxSteps: Int = 60): dev.ciel.task.TaskResult {
        val entry = GameCatalog.entryFor(packageName)
        val profile0 = profileFor(packageName)
        val plan = ObjectivePlanner.plan(objective, profile0.knowledge, entry?.systems ?: ObjectivePlanner.DEFAULT_SYSTEMS).let { p ->
            if (profile0.policy == AutomationPolicy.MENUS_ONLY) p.copy(steps = p.steps.filter { it.kind != StepKind.QUEST }) else p
        }
        val (r, error) = prepare(packageName, handle, plan.title)
        if (r == null) return dev.ciel.task.TaskResult(false, error!!, false)
        _activity.update { it?.copy(stepCount = plan.steps.size, stepTitle = plan.steps.firstOrNull()?.title ?: "") }
        handle.plan(plan.steps.map { it.title }, "Each step proven on screen; report lists anything not done")
        r.channel.say(false, "Plan: ${plan.steps.joinToString(" → ") { it.title }}")
        val results = mutableListOf<StepResult>()
        val budgets = plan.stepBudgets(maxSteps)
        try {
            r.agent.session(handle) { runStep ->
                for ((i, step) in plan.steps.withIndex()) {
                    handle.checkpoint()
                    if (r.session.blockerReason != null) break
                    _activity.update { it?.copy(stepIndex = i, stepTitle = step.title, currentAction = "Starting: ${step.title}", nextAction = plan.steps.getOrNull(i + 1)?.title) }
                    val res = runOneStep(r, runStep, step, budgets[i], "Overall objective from the user: $objective. Current step ${i + 1} of ${plan.steps.size}: ${step.title}.")
                    results += res
                    if (res.success) handle.markStepDone(i)
                }
            }
        } finally { finish(packageName, r, handle) }
        r.channel.questions.value.forEach { r.session.addAttention("Unanswered: ${it.question}") }
        val report = GameTaskReport.build(r.profile.name, results, plan.steps.size, r.session.attention + usageLine(r.session), r.session.blockerReason)
        val allDone = results.size == plan.steps.size && results.all { it.success }
        _activity.update { it?.copy(status = if (allDone) GameActivity.Status.FINISHED else GameActivity.Status.FAILED, currentAction = if (allDone) "Finished" else "Finished with items needing attention", finalReport = report.text(), needsAttention = !allDone) }
        r.channel.say(false, report.text())
        return dev.ciel.task.TaskResult(allDone, report.text(), verified = allDone)
    }

    private fun usageLine(s: GameSessionState) = "Decisions: ${s.stats.localDecisions} made on the phone, ${s.stats.aiCalls} by the AI model" +
        (if (!s.stats.aiAvailable) " (AI became unavailable; continued locally)" else "") + "."

    /** Asks the running queue to move on to its next task. */
    fun skipCurrentTask(taskId: String) { sessions[taskId]?.skipTask = true; channels[taskId]?.say(true, "Skip this task") }

    /**
     * Gameplay manager: runs the user's selected tasks in order (events first and other sensible
     * reordering unless the order is locked), repeating tasks until their stop rule, and reports
     * per task. Event deadlines read during the run are saved for the dashboard.
     */
    suspend fun runQueue(packageName: String, queue: GameQueue, handle: dev.ciel.task.TaskHandle, maxStepsPerRun: Int = 25): dev.ciel.task.TaskResult {
        val profile0 = profileFor(packageName)
        var ordered = QueuePlanner.order(queue, profile0.events)
        if (ordered.tasks.isEmpty()) return dev.ciel.task.TaskResult(false, "No tasks selected.", false)
        val (r, error) = prepare(packageName, handle, "Gameplay manager")
        if (r == null) return dev.ciel.task.TaskResult(false, error!!, false)
        val states = LinkedHashMap<String, GameActivity.QueueItem>()
        ordered.tasks.forEach { states[it.id] = GameActivity.QueueItem(it.label, GameActivity.ItemState.WAITING) }
        fun publish() = r.session.queue(states.values.toList())
        publish()
        (ordered.reasons + ordered.recommendations).forEach { r.channel.say(false, it) }
        handle.plan(ordered.tasks.map { it.label }, "Each task ends at its stop rule; results verified on screen")
        _activity.update { it?.copy(stepCount = ordered.tasks.size) }
        val lines = mutableListOf<String>()
        var remaining = ordered.tasks
        var index = 0
        try {
            r.agent.session(handle) { runStep ->
                while (remaining.isNotEmpty()) {
                    handle.checkpoint()
                    if (r.session.blockerReason != null) break
                    val task = remaining.first(); remaining = remaining.drop(1)
                    r.session.skipTask = false
                    states[task.id] = GameActivity.QueueItem(task.label, GameActivity.ItemState.RUNNING); publish()
                    _activity.update { it?.copy(stepIndex = index, stepTitle = task.label, currentAction = "Starting: ${task.label}", nextAction = remaining.firstOrNull()?.label) }
                    val steps = QueuePlanner.steps(task, r.session.knowledge)
                    var runs = 0; var verifiedRuns = 0; var stop: String? = null
                    loop@ while (runs < task.maxRuns) {
                        for (step in steps) {
                            handle.checkpoint()
                            val res = runOneStep(r, runStep, step, maxStepsPerRun,
                                "Task: ${task.label}" + (if (task.type.repeating) " (run ${runs + 1}, stop rule: ${task.stop.label}${if (task.stop == StopKind.RUNS) " ${task.number}" else ""})" else "") + ".")
                            if (res.skippedReason != null) { stop = res.skippedReason; break@loop }
                            if (!res.success) { stop = res.summary.lineSequence().firstOrNull()?.take(160) ?: "not completed"; if (task.type.repeating) break@loop }
                            if (res.success && step.kind != StepKind.INSPECT) verifiedRuns++
                        }
                        runs++
                        if (!task.type.repeating || task.stop == StopKind.ONCE) break
                    }
                    val state = when {
                        r.session.skipTask || stop == "Skipped by you." -> GameActivity.ItemState.SKIPPED
                        stop != null && verifiedRuns == 0 -> GameActivity.ItemState.BLOCKED
                        else -> GameActivity.ItemState.DONE
                    }
                    val note = if (task.type.repeating) "$verifiedRuns run(s) verified" + (stop?.let { "; stopped: $it" } ?: "") else stop
                    states[task.id] = GameActivity.QueueItem(task.label, state, note); publish()
                    lines += "${state.symbol} ${task.label}" + (note?.let { " — $it" } ?: "")
                    handle.markStepDone(index); index++
                    // After reading events, re-order what's left so expiring events come first.
                    if (task.type == TaskType.EVENT_SCAN && r.session.events.isNotEmpty() && queue.prioritiseEvents && !queue.lockedOrder) {
                        val again = QueuePlanner.order(queue.copy(tasks = remaining), r.session.events)
                        if (again.tasks != remaining) { remaining = again.tasks; again.reasons.forEach { r.channel.say(false, it) } }
                    }
                }
            }
        } finally { finish(packageName, r, handle) { it.copy(queue = queue) } }
        r.session.events.filter { it.urgency() == Urgency.HIGH }.forEach { r.session.addAttention("Event \"${it.name}\" ends soon (${it.shownText} when read).") }
        val done = states.values.count { it.state == GameActivity.ItemState.DONE }
        val allDone = done == ordered.tasks.size
        val text = buildString {
            append("**${r.profile.name} — ").append(if (allDone) "Queue complete" else "Queue finished: $done of ${ordered.tasks.size} tasks done").append("**\n\n")
            lines.forEach { append(it).append('\n') }
            if (r.session.attention.isNotEmpty()) { append("\nNeeds attention:\n"); r.session.attention.forEach { append("- ").append(it).append('\n') } }
            r.session.blockerReason?.let { append("\nStopped early: ").append(it).append('\n') }
            append("\n").append(usageLine(r.session))
        }.trim()
        _activity.update { it?.copy(status = if (allDone) GameActivity.Status.FINISHED else GameActivity.Status.FAILED, currentAction = if (allDone) "Queue complete" else "Queue finished — some tasks need attention",
            finalReport = text, needsAttention = !allDone, nextAction = null) }
        r.channel.say(false, text)
        return dev.ciel.task.TaskResult(allDone, text, verified = allDone)
    }

    /** Events from the profile that deserve a warning now (deduplicated by the caller). */
    suspend fun eventWarnings(packageName: String, now: Long = System.currentTimeMillis()): List<EventInfo> =
        profiles.get(packageName)?.events?.filter { it.urgency(now) == Urgency.HIGH } ?: emptyList()

    /**
     * Context for answering "what are you doing / why" while a task runs: live state, plan,
     * recent verified actions, the current screen reading and relevant knowledge.
     */
    fun liveContext(taskId: String?, question: String = ""): String? {
        val a = _activity.value?.takeIf { taskId == null || it.taskId == taskId } ?: return null
        val s = sessions[a.taskId]
        return buildString {
            append("Game: ${a.game}. Task: ${a.title}. Status: ${a.status.label}. ${a.progressText}. Current step: ${a.stepTitle}. Current action: ${a.currentAction}.\n")
            s?.lastReading?.let { append("Current screen: ${it.summary()}. Evidence: ${it.evidence.take(6).joinToString()}.\n") }
            s?.log?.takeLast(8)?.let { if (it.isNotEmpty()) append("Recent actions (with what was observed):\n").append(it.joinToString("\n") { l -> "- $l" }).append('\n') }
            s?.let { append(it.policy.describe()).append('\n') }
            channels[a.taskId]?.questions?.value?.forEach { append("Waiting for the user's decision: ${it.question}\n") }
            s?.knowledge?.let { kb -> append("Relevant knowledge:\n").append(kb.briefing(question.ifBlank { a.stepTitle }, 6)) }
            a.finalReport?.let { append("\nFinal report:\n").append(it) }
        }
    }

    /** Answers an informational question without touching the screen or stopping the task. */
    suspend fun answer(taskId: String?, question: String): String {
        val ctx = liveContext(taskId, question) ?: return "No game task is running right now."
        currentChannel()?.say(true, question)
        val reply = runCatching { answerer?.answer(ctx, question) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: _activity.value?.let { a -> "I'm ${a.currentAction.replaceFirstChar { it.lowercase() }} (${a.progressText}, step: ${a.stepTitle}). ${sessions[a.taskId]?.lastReading?.let { "The screen looks like: ${it.summary()}." } ?: ""}".trim() }
            ?: "No game task is running right now."
        currentChannel()?.say(false, reply)
        return reply
    }

    /** Records verified successful step sequences, failures, and newly seen screens. */
    internal suspend fun learn(profile: GameProfile, objective: String, run: AgentRun) {
        val effective = run.steps.filter { it.action != null && it.screenChanged == true }.map { it.action!!.describe() }
        val screens = profile.knownScreens.toMutableMap()
        run.steps.forEach { s -> screens.putIfAbsent(s.screenSignature, "seen during \"${objective.take(40)}\"") }
        val existing = profile.strategies.firstOrNull { it.objective.equals(objective, ignoreCase = true) }
        val strategies = profile.strategies.filter { it !== existing }.toMutableList()
        if (run.result.success && run.result.verified) {
            strategies += (existing?.copy(steps = effective.takeLast(30), successCount = existing.successCount + 1, gameVersion = profile.versionName, lastUsed = System.currentTimeMillis())
                ?: VerifiedStrategy(objective, effective.takeLast(30), gameVersion = profile.versionName))
        } else if (existing != null) {
            strategies += existing.copy(failureCount = existing.failureCount + 1, lastUsed = System.currentTimeMillis())
        }
        profiles.save(profile.copy(
            knownScreens = screens.entries.toList().takeLast(200).associate { it.key to it.value },
            strategies = strategies.sortedByDescending { it.lastUsed }.take(25),
            // A verified success on the new version clears the interface-change flag.
            interfaceChangeSuspected = profile.interfaceChangeSuspected && !(run.result.success && run.result.verified),
            updatedAt = System.currentTimeMillis(),
        ))
    }
}

class GameMasterSkill(private val engine: GameEngine, private val profiles: GameProfileRepository) : Skill {
    override val id = "game_master"
    override val displayName = "Game Master"
    override val description = "Recognise games, keep game profiles, and play permitted single-player tasks autonomously."
    override val requiredCapabilities = setOf(Capability.ACCESSIBILITY)
    override val tools = listOf(
        ToolSpec("identify_current_game", "Identify the game on screen and summarise its profile.", Schema.obj()),
        ToolSpec("play_game_task",
            "Start an autonomous task in a game, e.g. 'complete my daily quests', 'collect rewards', 'check which buildings need attention', 'explore and learn how the game works'. Ciel plans the steps, follows the user's spending rules and reports. Runs in the background; returns a task id. Not complete until task_status says COMPLETED and verified.",
            Schema.obj(listOf("game", "objective"),
                "game" to Schema.str("Game name or package"),
                "objective" to Schema.str("What to accomplish, with a clear completion condition"))),
        ToolSpec("game_knowledge", "Search what Ciel has learned about a game (locations, actions, mechanics, risks) with confidence levels.",
            Schema.obj(listOf("game", "query"), "game" to Schema.str("Game name or package"), "query" to Schema.str("What to look up, e.g. 'quests' or 'production'"))),
        ToolSpec("add_game_note", "Save a verified fact about a game to its profile.",
            Schema.obj(listOf("game", "kind", "note"),
                "game" to Schema.str("Game name or package"),
                "kind" to Schema.str("Kind", listOf("navigation", "mechanic", "quest", "limitation")),
                "note" to Schema.str("The fact"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val device = ctx.device ?: return ToolOutcome.fail("Device control unavailable.")
        return when (tool) {
            "identify_current_game" -> {
                val screen = device.observe(ctx.generation)
                val pkg = screen.packageName ?: return ToolOutcome.fail("Could not determine the foreground app.")
                val p = engine.profileFor(pkg)
                ToolOutcome.ok(p.knowledgeSummary() + "Automation policy: ${p.policy}\nCurrent screen ${if (screen.signature() in p.knownScreens) "is known: ${p.knownScreens[screen.signature()]}" else "is new"}.", true)
            }
            "play_game_task" -> {
                val pkg = SystemSkill.resolveApp(args.reqStr("game"), device.installedApps())
                    ?: return ToolOutcome.fail("No installed game matches \"${args.str("game")}\".")
                val objective = args.reqStr("objective")
                val id = ctx.tasks.start(objective, "game") { handle ->
                    val open = device.perform(handle.id, handle.generation, DeviceAction.OpenApp(pkg), null)
                    if (open !is dev.ciel.task.ActionOutcome.Done) return@start dev.ciel.task.TaskResult(false, "Could not open the game.", false)
                    kotlinx.coroutines.delay(4000) // games take a while to load
                    engine.runObjective(pkg, objective, handle)
                }
                ToolOutcome.ok("Game task started (id $id). It runs in the background and is NOT complete yet.")
            }
            "game_knowledge" -> {
                val pkg = SystemSkill.resolveApp(args.reqStr("game"), device.installedApps()) ?: return ToolOutcome.fail("Game not found.")
                val p = engine.profileFor(pkg)
                val hits = p.knowledge.search(args.reqStr("query"), 8)
                if (hits.isEmpty()) ToolOutcome.ok("Nothing learned yet about that in ${p.name}. Ask Ciel to explore the game first.", true)
                else ToolOutcome.ok(GameKnowledgeBase(hits).briefing(args.reqStr("query"), 8), true)
            }
            "add_game_note" -> {
                val pkg = SystemSkill.resolveApp(args.reqStr("game"), device.installedApps()) ?: return ToolOutcome.fail("Game not found.")
                val p = engine.profileFor(pkg)
                val note = args.reqStr("note")
                val updated = when (args.str("kind")) {
                    "navigation" -> p.copy(navigationNotes = (p.navigationNotes + note).distinct().takeLast(40))
                    "mechanic" -> p.copy(mechanics = (p.mechanics + note).distinct().takeLast(40))
                    "quest" -> p.copy(questStructure = (p.questStructure + note).distinct().takeLast(40))
                    else -> p.copy(limitations = (p.limitations + note).distinct().takeLast(40))
                }
                profiles.save(updated.copy(updatedAt = System.currentTimeMillis()))
                ToolOutcome.ok("Saved to ${p.name}'s profile.", true)
            }
            else -> ToolOutcome.fail("Unknown tool $tool")
        }
    }
}
