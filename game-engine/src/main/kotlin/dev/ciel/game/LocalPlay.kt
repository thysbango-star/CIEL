package dev.ciel.game

import dev.ciel.task.AgentContext
import dev.ciel.task.DeviceAction
import dev.ciel.task.GlobalActionType
import dev.ciel.task.PlannerDecision
import dev.ciel.task.ScreenSnapshot

/** Measured usage for one game session (never estimated). */
data class UsageStats(
    val localDecisions: Int = 0,
    val aiCalls: Int = 0,
    val aiFailures: Int = 0,
    val aiAvailable: Boolean = true,
) {
    val mode: String get() = when {
        !aiAvailable -> "Local only (AI unavailable)"
        aiCalls == 0 -> "Local"
        else -> "Local + AI"
    }
}

/**
 * Deterministic, on-phone decisions for routine game work — no AI call:
 *  - acknowledge reward / result screens, close ordinary pop-ups;
 *  - stop on "not enough stamina" (never buys refills) and on defeats;
 *  - claim/collect buttons in claim steps, and finish when none remain;
 *  - navigate with learned routes and the step's menu names, back to the main screen when lost;
 *  - finish read-only inspections from the on-screen text when no AI is available.
 * Returns null when the situation needs real interpretation (then an AI model is asked, if any).
 */
class LocalGamePlanner(private val session: GameSessionState) {
    private val tappedHere = HashSet<String>()
    private var backs = 0
    var claimsMade = 0; private set

    fun reset() { tappedHere.clear(); backs = 0; claimsMade = 0 }

    private val insufficient = Regex("(?i)not enough|insufficient|out of stamina|no (more )?attempts|lack of|need more")
    private val keywords = mapOf(
        "Quests" to listOf("quest", "quests", "mission", "missions"),
        "Events" to listOf("event", "events"),
        "Production" to listOf("production"),
        "Rewards and mail" to listOf("mail", "mailbox", "gift", "gifts", "rewards"),
        "Characters" to listOf("cookies", "cookie", "characters", "heroes"),
        "Cookies" to listOf("cookies", "cookie"),
        "Battles" to listOf("world exploration", "adventure", "story", "episode", "explore"),
        "Inventory" to listOf("storage", "inventory", "bag", "items"),
        "Research" to listOf("research", "lab"),
    )

    fun decide(ctx: AgentContext, r: ScreenReading): PlannerDecision? {
        val step = session.currentStep ?: return null
        // A local action that already had no effect on this screen: hand over to the AI.
        if (ctx.warnings.any { it.startsWith("Already tried") }) return null
        val screen = ctx.screen
        val texts = screen.nodes.mapNotNull { it.text ?: it.contentDescription }.joinToString("  ")

        // Not enough stamina / attempts: this run cannot happen. Close the offer, never buy.
        if (insufficient.containsMatchIn(texts) && (r.state == GameScreenState.POPUP || r.state == GameScreenState.SHOP || r.state == GameScreenState.UNKNOWN || step.kind == StepKind.QUEST)) {
            return PlannerDecision.Fail("Stopped: the game says resources are insufficient (\"${insufficient.find(texts)!!.value}\"). Ciel does not buy refills.")
        }
        // A battle result ends one run.
        if (r.state == GameScreenState.BATTLE_RESULT && ctx.history.isEmpty()) {
            // A result left over from the previous run: dismiss it, it is not a new run.
            return pick(r, screen, ElementKind.CONFIRM, ElementKind.CLOSE)?.let { tap(it, "Dismiss the previous result") }
                ?: PlannerDecision.Act(DeviceAction.Global(GlobalActionType.BACK), "Dismiss the previous result.", "the stage screen")
        }
        if (r.state == GameScreenState.BATTLE_RESULT && r.confidence != Confidence.LOW) {
            val lost = Regex("(?i)\\bdefeat|failed|lose\\b").containsMatchIn(texts)
            return if (lost) PlannerDecision.Fail("Defeat: this stage needs a stronger team or formation. Stopping repeats of it.")
            else PlannerDecision.Complete("Run finished: ${r.evidence.take(3).joinToString()}", "Result screen shows ${r.evidence.joinToString()}")
        }
        // Reward screens: acknowledge.
        if (r.state == GameScreenState.REWARD) {
            pick(r, screen, ElementKind.CONFIRM, ElementKind.CLOSE)?.let { return tap(it, "Acknowledge the reward") }
        }
        // Ordinary pop-ups (not the thing we're looking for): close them.
        if (r.state == GameScreenState.POPUP && step.kind != StepKind.QUEST) {
            pick(r, screen, ElementKind.CLOSE)?.let { return tap(it, "Close a pop-up") }
        }
        val here = GameSessionState.systemOf(r.state)
        val onTarget = here.equals(step.system, true) || (step.system == "Rewards and mail" && r.state == GameScreenState.REWARD)

        // Claim / collect steps.
        if (step.kind == StepKind.CLAIM || step.kind == StepKind.COLLECT) {
            val claim = r.elements.firstOrNull { it.kind == ElementKind.CLAIM && it.nodeId != null && key(screen, it) !in tappedHere }
            if (claim != null && (onTarget || step.kind == StepKind.COLLECT || claimsMade > 0)) {
                tappedHere += key(screen, claim); claimsMade++
                return tap(claim, "Claim \"${claim.label}\"", "the reward is received")
            }
            // Verified by our own taps: once something was claimed and no buttons remain, the step is done.
            if (claim == null && (claimsMade > 0 || (onTarget && r.confidence != Confidence.LOW))) {
                return PlannerDecision.Complete(if (claimsMade > 0) "Claimed $claimsMade reward(s); no claim buttons remain." else "Nothing to claim right now.",
                    "On ${r.state.label}: no claim buttons visible (read on the phone).")
            }
        }
        // Navigation towards the step's system.
        if (!onTarget) {
            route(step.system, screen)?.let { (id, label) -> return PlannerDecision.Act(DeviceAction.ClickNode(id, label), "Learned route to ${step.system}: tap \"$label\".", "the ${step.system.lowercase()} screen opens") }
            byName(step.system, screen)?.let { (id, label) -> return PlannerDecision.Act(DeviceAction.ClickNode(id, label), "Open ${step.system}: \"$label\" is on screen.", "the ${step.system.lowercase()} screen opens") }
            if (r.state != GameScreenState.MAIN && r.state != GameScreenState.UNKNOWN && backs < 3) {
                backs++
                return PlannerDecision.Act(DeviceAction.Global(GlobalActionType.BACK), "Return towards the main screen to find ${step.system}.", "a previous screen")
            }
        }
        return null
    }

    /** Used only when no AI is available: finish read-only inspections from the screen's text. */
    fun offline(ctx: AgentContext, r: ScreenReading): PlannerDecision? {
        val step = session.currentStep ?: return null
        val onTarget = GameSessionState.systemOf(r.state).equals(step.system, true)
        if ((step.kind == StepKind.CLAIM || step.kind == StepKind.COLLECT) && onTarget && r.textAvailable && !r.has(ElementKind.CLAIM)) {
            return PlannerDecision.Complete("Nothing (more) to claim here.", "No claim buttons on the ${r.state.label} screen (read on the phone).")
        }
        if ((step.kind == StepKind.INSPECT || step.kind == StepKind.EXPLORE) && onTarget && r.textAvailable) {
            val seen = ctx.screen.nodes.mapNotNull { it.text }.filter { it.length in 2..60 }.distinct().take(25)
            return PlannerDecision.Complete("Read on the phone (no AI): ${r.summary()}. Visible: ${seen.joinToString(" · ")}", "Text read from the ${r.state.label} screen")
        }
        return null
    }

    private fun pick(r: ScreenReading, screen: ScreenSnapshot, vararg kinds: ElementKind) =
        kinds.asSequence().mapNotNull { k -> r.elements.firstOrNull { it.kind == k && it.nodeId != null && key(screen, it) !in tappedHere } }.firstOrNull()

    private fun tap(e: RecognisedElement, why: String, expect: String = "the screen changes"): PlannerDecision {
        return PlannerDecision.Act(DeviceAction.ClickNode(e.nodeId!!, e.label), why, expect)
    }

    private fun key(s: ScreenSnapshot, e: RecognisedElement) = s.signature() + "#" + e.label

    private fun route(system: String, screen: ScreenSnapshot): Pair<Int, String>? {
        val labels = session.knowledge.entries
            .filter { it.kind == KnowledgeKind.LOCATION && it.system.equals(system, true) && it.confidence != Confidence.LOW }
            .sortedByDescending { it.confidence }
            .flatMap { e -> e.howToReach.mapNotNull { Regex("tap \"(.+?)\"").find(it)?.groupValues?.get(1) } }
        for (l in labels) screen.nodes.firstOrNull { n -> (n.text ?: n.contentDescription)?.trim()?.equals(l, true) == true }?.let { return it.id to l }
        return null
    }

    private fun byName(system: String, screen: ScreenSnapshot): Pair<Int, String>? {
        val words = keywords[system] ?: listOf(system.lowercase())
        return screen.nodes.firstOrNull { n ->
            val t = (n.text ?: n.contentDescription)?.lowercase()?.trim() ?: return@firstOrNull false
            t.length <= 24 && words.any { GameScreenReader.containsWord(t, it) }
        }?.let { it.id to (it.text ?: it.contentDescription!!) }
    }
}
