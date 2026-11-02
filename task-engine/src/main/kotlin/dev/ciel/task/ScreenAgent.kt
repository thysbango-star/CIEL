package dev.ciel.task

import kotlinx.coroutines.delay

data class AgentStepRecord(
    val index: Int,
    val screenSignature: String,
    val action: DeviceAction?,
    val rationale: String,
    val outcome: String,
    /** Whether the screen observably changed after the action. */
    val screenChanged: Boolean?,
)

data class AgentContext(
    val objective: String,
    /** Profile knowledge, verified strategies, user preferences — background, not instructions. */
    val knowledge: String?,
    val screen: ScreenSnapshot,
    val history: List<AgentStepRecord>,
    val warnings: List<String>,
    val stepsRemaining: Int,
)

sealed class PlannerDecision {
    data class Act(val action: DeviceAction, val rationale: String, val expectation: String? = null) : PlannerDecision()
    /** [evidence] must describe what on the current screen proves completion. */
    data class Complete(val summary: String, val evidence: String) : PlannerDecision()
    data class Fail(val reason: String) : PlannerDecision()
}

/** Decides the next step. Implemented by the language-model planner in core-ai; faked in tests. */
fun interface ActionPlanner {
    suspend fun next(context: AgentContext): PlannerDecision
}

data class AgentOptions(
    val maxSteps: Int = 40,
    val settleDelayMs: Long = 900,
    val includeScreenshots: Boolean = false,
    /** Same action on the same screen with no visible change this many times → considered failed. */
    val maxIneffectiveRepeats: Int = 2,
    val knowledge: String? = null,
)

data class AgentRun(val result: TaskResult, val steps: List<AgentStepRecord>, val finalScreen: ScreenSnapshot?)

/**
 * Generic observe → decide → act → verify loop used for app automation and the game engine.
 * - Holds the SCREEN lock for its duration, so parallel tasks cannot interfere.
 * - Every action goes through [GuardedDevice] (emergency stop + risk policy + confirmation).
 * - Tracks ineffective actions per screen and refuses to blindly repeat them.
 * - Completion is only accepted against a fresh observation taken after the last action.
 */
class ScreenAgent(
    private val device: GuardedDevice,
    private val planner: ActionPlanner,
    private val locks: ResourceLocks,
) {
    suspend fun run(handle: TaskHandle, objective: String, options: AgentOptions = AgentOptions()): AgentRun {
        // Only one task may drive the screen at a time; others wait visibly in the queue.
        if (locks.isBusy(ResourceLocks.Resource.SCREEN)) {
            handle.update { it.copy(status = TaskStatus.QUEUED, currentStep = "Waiting for another task to finish") }
        }
        return locks.withResource(ResourceLocks.Resource.SCREEN) {
            handle.update { if (it.status == TaskStatus.QUEUED) it.copy(status = TaskStatus.RUNNING, currentStep = "Starting") else it }
            loop(handle, objective, options)
        }
    }

    private suspend fun loop(handle: TaskHandle, objective: String, options: AgentOptions): AgentRun {
        if (!device.isAvailable) {
            return AgentRun(TaskResult(false, "Screen control is unavailable: enable Ciel's accessibility service in Settings → Accessibility.", false), emptyList(), null)
        }
        val history = mutableListOf<AgentStepRecord>()
        // (screenSignature, actionDescription) -> times it produced no change
        val ineffective = HashMap<Pair<String, String>, Int>()
        var blockedRepeats = 0
        var screen = device.observe(handle.generation, options.includeScreenshots)

        for (step in 0 until options.maxSteps) {
            // Honours pause and emergency stop between steps; after a pause the user may have
            // changed the screen, so look again before deciding.
            if (handle.checkpoint()) screen = device.observe(handle.generation, options.includeScreenshots)
            val sig = screen.signature()
            val warnings = ineffective.filter { it.key.first == sig && it.value >= options.maxIneffectiveRepeats }
                .map { "Already tried \"${it.key.second}\" on this screen ${it.value} times with no visible effect. Choose a different approach." }
            handle.step("Analysing screen (step ${step + 1})", screen.packageName)

            when (val decision = planner.next(AgentContext(objective, options.knowledge, screen, history.toList(), warnings, options.maxSteps - step))) {
                is PlannerDecision.Complete -> {
                    history += AgentStepRecord(step, sig, null, decision.summary, "Completed", null)
                    return AgentRun(TaskResult(true, "${decision.summary}\nEvidence: ${decision.evidence}", verified = true), history, screen)
                }
                is PlannerDecision.Fail -> {
                    history += AgentStepRecord(step, sig, null, decision.reason, "Gave up", null)
                    return AgentRun(TaskResult(false, decision.reason, verified = false), history, screen)
                }
                is PlannerDecision.Act -> {
                    val key = sig to decision.action.describe()
                    if ((ineffective[key] ?: 0) >= options.maxIneffectiveRepeats) {
                        blockedRepeats++
                        handle.error("Refused to repeat an ineffective action: ${decision.action.describe()}")
                        history += AgentStepRecord(step, sig, decision.action, decision.rationale, "Refused: repeated ineffective action", false)
                        if (blockedRepeats >= 3) {
                            return AgentRun(TaskResult(false, "Stuck: the same actions keep failing on this screen. The interface may have changed.", false), history, screen)
                        }
                        continue
                    }
                    handle.step(decision.action.describe(), screen.packageName)
                    val outcome = device.perform(handle.id, handle.generation, decision.action, screen)
                    if (decision.action !is DeviceAction.Wait) delay(options.settleDelayMs)
                    val after = device.observe(handle.generation, options.includeScreenshots)
                    val changed = after.signature() != sig || after.packageName != screen.packageName ||
                        after.nodes.map { it.label } != screen.nodes.map { it.label }
                    val outcomeText = when (outcome) {
                        is ActionOutcome.Done -> if (changed) "Done; screen changed" else "Done; no visible change"
                        is ActionOutcome.Failed -> "Failed: ${outcome.reason}"
                        is ActionOutcome.Unsupported -> "Unsupported: ${outcome.reason}"
                    }
                    if (!changed || outcome !is ActionOutcome.Done) ineffective[key] = (ineffective[key] ?: 0) + 1
                    if (outcome !is ActionOutcome.Done) handle.error(outcomeText)
                    history += AgentStepRecord(step, sig, decision.action, decision.rationale, outcomeText, changed)
                    screen = after
                }
            }
        }
        return AgentRun(TaskResult(false, "Reached the step limit (${options.maxSteps}) without confirming completion.", false), history, screen)
    }
}
