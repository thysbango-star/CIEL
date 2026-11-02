package dev.ciel.game

/** What the user is asking for in a game. */
enum class GameIntent { DAILY_QUESTS, COLLECT_REWARDS, UPGRADE_BUILDINGS, CHECK_BUILDINGS, PRODUCTION, EXPLORE, IMPROVE, CUSTOM }

enum class StepKind { INSPECT, COLLECT, CLAIM, UPGRADE, PRODUCE, QUEST, EXPLORE }

data class GamePlanStep(
    val title: String,
    /** Game system this step works in (for knowledge lookup and learning). */
    val system: String,
    val kind: StepKind,
    /** Instructions for the screen agent, including how completion is proven. */
    val objective: String,
    /** Read-only steps may open and read, never confirm, collect, spend or start anything. */
    val readOnly: Boolean = false,
    /** Share of the task's step budget. */
    val weight: Int = 1,
)

data class GamePlan(val intent: GameIntent, val title: String, val steps: List<GamePlanStep>) {
    fun stepBudgets(total: Int): List<Int> {
        val w = steps.sumOf { it.weight }.coerceAtLeast(1)
        return steps.map { maxOf(6, total * it.weight / w) }
    }
}

/**
 * Turns a natural-language game objective into a prioritised plan. The plan's order follows the
 * brief: quick wins (collect, claim) first, then objectives, then spending decisions, then report.
 * Every step must end with on-screen proof, otherwise it counts as not done.
 */
object ObjectivePlanner {
    private const val READ_ONLY = "Only open menus, read and go back. Do NOT confirm, collect, upgrade, start, buy or spend anything."
    private const val PROOF = "Finish with \"done\" only when the current screen proves it; describe what you saw in the summary."

    fun intentOf(text: String): GameIntent {
        val t = text.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("explore", "learn how", "learn the game", "discover", "map out") -> GameIntent.EXPLORE
            has("improve", "progress", "grow my kingdom", "manage my kingdom", "what needs to be done", "needs attention") && !has("upgrade my") -> GameIntent.IMPROVE
            has("daily quest", "daily mission", "quests", "missions", "dailies") -> GameIntent.DAILY_QUESTS
            has("which buildings", "check building", "buildings need", "inspect building") -> GameIntent.CHECK_BUILDINGS
            has("upgrade") -> GameIntent.UPGRADE_BUILDINGS
            has("production", "produce", "craft") -> GameIntent.PRODUCTION
            has("collect", "claim", "reward", "loot", "rewards") -> GameIntent.COLLECT_REWARDS
            else -> GameIntent.CUSTOM
        }
    }

    fun plan(objective: String, knowledge: GameKnowledgeBase = GameKnowledgeBase(), systems: List<String> = DEFAULT_SYSTEMS): GamePlan {
        val inspectQuests = GamePlanStep("Inspect quests", "Quests", StepKind.INSPECT,
            "Find the quests/missions menu (use the learned route if one is known, but verify it on screen). Read every visible quest category and objective. Note which are complete, incomplete and claimable. $READ_ONLY $PROOF Summary: list the objectives with their status.", readOnly = true, weight = 2)
        val claimQuests = GamePlanStep("Claim completed quest rewards", "Quests", StepKind.CLAIM,
            "In the quests/missions menu, claim every reward that is already completed (\"Claim\"/\"Collect\" buttons). After each claim, check the reward was received before the next. Never tap anything that costs premium currency or money. $PROOF", weight = 2)
        val collectProduction = GamePlanStep("Collect completed production", "Production", StepKind.COLLECT,
            "Collect goods and resources that are ready around the main screen (ready indicators, \"Collect\"). Check each collection succeeded (the indicator disappears or a count changes). Do not start new production in this step. $PROOF", weight = 2)
        val claimRewards = GamePlanStep("Claim free rewards", "Rewards", StepKind.CLAIM,
            "Look for free claimable rewards (mailbox, daily rewards, event rewards, notification badges). Claim only free rewards; skip anything that needs payment or premium currency. Verify each claim. $PROOF", weight = 2)
        val easyQuests = GamePlanStep("Complete easy objectives", "Quests", StepKind.QUEST,
            "Pick the incomplete quest objectives that can be done quickly with ordinary actions (e.g. collect, produce, visit a place). Go to the place each objective needs, do it, return and check the quest progress changed. Skip objectives needing battles you would likely lose, premium currency or long timers, and say which you skipped. $PROOF", weight = 4)
        val inspectBuildings = GamePlanStep("Inspect buildings", "Buildings", StepKind.INSPECT,
            "Look over the buildings on the main screen. For buildings showing a badge, a finished timer or an upgrade arrow, open their info and read: name, level, production status, upgrade availability and cost/requirements. $READ_ONLY $PROOF Summary: list buildings that need attention and why.", readOnly = true, weight = 3)
        val upgrade = GamePlanStep("Perform permitted upgrades", "Buildings", StepKind.UPGRADE,
            "Upgrade buildings only where an upgrade is available, the cost is affordable and it fits the objective. Read the requirements first. Your actions are checked against the user's spending rules; if one needs the user's decision, continue with others. After each upgrade, verify the level increased. $PROOF", weight = 3)
        val produce = GamePlanStep("Start useful production", "Production", StepKind.PRODUCE,
            "In production buildings with free queue slots, start production of items that are needed (by quests or as materials). Avoid using scarce materials. Verify each production started (a timer appears). $PROOF", weight = 3)
        val recheck = GamePlanStep("Re-check quests and claim", "Quests", StepKind.CLAIM,
            "Open the quests/missions menu again and claim any reward that became completed. $PROOF", weight = 1)

        val steps = when (intentOf(objective)) {
            GameIntent.DAILY_QUESTS -> listOf(inspectQuests, claimQuests, easyQuests, recheck)
            GameIntent.COLLECT_REWARDS -> listOf(collectProduction, claimRewards, claimQuests)
            GameIntent.UPGRADE_BUILDINGS -> listOf(collectProduction, inspectBuildings, upgrade)
            GameIntent.CHECK_BUILDINGS -> listOf(inspectBuildings)
            GameIntent.PRODUCTION -> listOf(collectProduction, produce)
            GameIntent.IMPROVE -> listOf(collectProduction, claimRewards, inspectQuests, claimQuests, easyQuests, inspectBuildings, upgrade, produce)
            GameIntent.EXPLORE -> exploration(systems, knowledge)
            GameIntent.CUSTOM -> listOf(GamePlanStep(objective.take(60), "General", StepKind.QUEST, "$objective\n$PROOF", weight = 1))
        }
        val title = when (intentOf(objective)) {
            GameIntent.DAILY_QUESTS -> "Completing daily quests"
            GameIntent.COLLECT_REWARDS -> "Collecting rewards"
            GameIntent.UPGRADE_BUILDINGS -> "Upgrading buildings"
            GameIntent.CHECK_BUILDINGS -> "Checking buildings"
            GameIntent.PRODUCTION -> "Managing production"
            GameIntent.IMPROVE -> "Improving the kingdom"
            GameIntent.EXPLORE -> "Exploring and learning"
            GameIntent.CUSTOM -> objective.take(40)
        }
        return GamePlan(intentOf(objective), title, steps)
    }

    /** Controlled exploration: survey, then one system at a time, least-known first. */
    private fun exploration(systems: List<String>, kb: GameKnowledgeBase): List<GamePlanStep> {
        val survey = GamePlanStep("Survey the main screen", "Main", StepKind.EXPLORE,
            "Read the current screen carefully without tapping anything except closing pop-ups. If you are not on the game's main screen, go back until you are. $PROOF Summary: list every button, menu, icon label, currency and counter you can see, with where it is on screen (e.g. top-left).", readOnly = true, weight = 2)
        val known = kb.entries.groupBy { it.system.lowercase() }
        val ordered = systems.sortedBy { s -> known[s.lowercase()]?.maxOfOrNull { it.confidence.ordinal } ?: -1 }
        return listOf(survey) + ordered.map { s ->
            GamePlanStep("Explore: $s", s, StepKind.EXPLORE,
                "Find and open the $s menu if this game has one. Read what it shows: purpose, sections/tabs, actions offered, resources it uses, timers and any costs. $READ_ONLY " +
                    "Then return to the main screen. If you cannot find it within a few taps, fail and say what you tried. $PROOF Summary: where it is (route), what it does, actions available, resources used, risks.",
                readOnly = true, weight = 2)
        }
    }

    val DEFAULT_SYSTEMS = listOf("Quests", "Production", "Buildings", "Rewards and mail", "Characters", "Events", "Inventory", "Research")
}

/** Outcome of one plan step. */
data class StepResult(
    val step: GamePlanStep,
    val success: Boolean,
    val verified: Boolean,
    val summary: String,
    val skippedReason: String? = null,
)

/**
 * The report shown after a game task. Never claims more than was verified: unverified steps are
 * listed as not done, partial runs say so plainly.
 */
data class GameTaskReport(
    val game: String,
    val completed: List<String>,
    val needsAttention: List<String>,
    val skipped: List<String>,
    val partial: Boolean,
    val stoppedEarly: String?,
) {
    fun text(): String = buildString {
        append("**$game — ").append(when { completed.isEmpty() -> "Task not completed"; partial -> "Task partially complete"; else -> "Task complete" }).append("**\n")
        if (completed.isNotEmpty()) { append("\nCompleted:\n"); completed.forEach { append("- ").append(it).append('\n') } }
        if (needsAttention.isNotEmpty()) { append("\nNeeds attention:\n"); needsAttention.forEach { append("- ").append(it).append('\n') } }
        if (skipped.isNotEmpty()) { append("\nSkipped:\n"); skipped.forEach { append("- ").append(it).append('\n') } }
        stoppedEarly?.let { append("\nStopped early: ").append(it).append('\n') }
    }.trim()

    companion object {
        fun build(game: String, results: List<StepResult>, plannedSteps: Int, attention: List<String>, stoppedEarly: String?): GameTaskReport {
            val done = results.filter { it.success && it.verified }
            val failed = results.filter { !(it.success && it.verified) && it.skippedReason == null }
            val skipped = results.filter { it.skippedReason != null }
            val notReached = plannedSteps - results.size
            val needs = attention.toMutableList()
            failed.forEach { needs += "${it.step.title}: not completed — ${it.summary.lineSequence().firstOrNull()?.take(160) ?: "no result"}" }
            if (notReached > 0) needs += "$notReached planned step(s) were not reached."
            return GameTaskReport(
                game = game,
                completed = done.map { it.step.title + (it.summary.lineSequence().firstOrNull()?.takeIf { s -> s.isNotBlank() }?.let { s -> " — ${s.take(160)}" } ?: "") },
                needsAttention = needs.distinct(),
                skipped = skipped.map { "${it.step.title}: ${it.skippedReason}" },
                partial = done.size < plannedSteps,
                stoppedEarly = stoppedEarly,
            )
        }
    }
}
