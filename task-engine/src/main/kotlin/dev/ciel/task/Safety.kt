package dev.ciel.task

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Risk {
    /** Navigation, reading, scrolling. */
    ROUTINE,
    /** Needs explicit user confirmation: purchases, sending, deleting, security settings, account changes. */
    CONSEQUENTIAL,
    /** Never performed automatically (e.g. human-verification challenges). */
    FORBIDDEN,
}

data class RiskAssessment(val risk: Risk, val reason: String)

data class ConfirmationRequest(
    val title: String,
    val detail: String,
    val risk: Risk,
    val actionDescription: String,
)

/** Implemented by the UI (dialog / overlay card / voice prompt). Returns true only on explicit approval. */
fun interface ConfirmationGate {
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

/**
 * Classifies a pending action against the current screen. The policy is deliberately
 * conservative: when the target looks like money, sending, deleting, security or account
 * changes, the user must approve. Human-verification screens stop automation entirely.
 */
object ActionPolicy {
    private val purchase = Regex(
        "(?i)\\b(buy|purchase|pay|checkout|subscribe|order now|top ?up|recharge|add funds|confirm payment|in-app)\\b|[\\$€£¥₹]\\s?\\d|\\d+[.,]\\d{2}\\s?(usd|eur|gbp|zar|r)\\b|\\bR\\s?\\d+[.,]\\d{2}"
    )
    private val sending = Regex("(?i)\\b(send|post|publish|share|reply|tweet|submit)\\b")
    private val destructive = Regex("(?i)\\b(delete|remove|erase|uninstall|reset|wipe|clear (all|data)|factory)\\b")
    private val security = Regex("(?i)\\b(password|passcode|pin|fingerprint|security|2fa|two-factor|log ?out|sign ?out|account settings|permissions?)\\b")
    private val humanCheck = Regex("(?i)(captcha|i'?m not a robot|verify (that )?you are human|human verification)")

    fun assess(action: DeviceAction, screen: ScreenSnapshot?): RiskAssessment {
        if (screen != null && screen.nodes.any { n -> humanCheck.containsMatchIn(n.label) }) {
            return RiskAssessment(Risk.FORBIDDEN, "A human-verification challenge is on screen. Ciel does not solve these.")
        }
        val targetText: String? = when (action) {
            is DeviceAction.ClickNode -> screen?.nodes?.firstOrNull { it.id == action.nodeId }?.label ?: action.expectedLabel
            is DeviceAction.ClickText -> action.text
            is DeviceAction.TapAt -> screen?.let { nodeAt(it, action.x, action.y)?.label }
            is DeviceAction.TapRel -> screen?.takeIf { it.screenWidth > 0 }?.let { s -> action.toPixels(s.screenWidth, s.screenHeight).let { p -> nodeAt(s, p.x, p.y)?.label } }
            is DeviceAction.LongPressAt -> screen?.let { nodeAt(it, action.x, action.y)?.label }
            is DeviceAction.OpenSettings -> action.screen
            else -> null
        }
        if (action is DeviceAction.OpenSettings && security.containsMatchIn(action.screen)) {
            return RiskAssessment(Risk.CONSEQUENTIAL, "Opening security-related settings.")
        }
        val tapPoint = when (action) {
            is DeviceAction.TapAt -> action
            is DeviceAction.TapRel -> screen?.takeIf { it.screenWidth > 0 }?.let { action.toPixels(it.screenWidth, it.screenHeight) }
            else -> null
        }
        if (tapPoint != null && targetText == null) {
            // Coordinate taps on unlabelled areas (common in games) are routine unless the screen
            // as a whole is a store / payment screen.
            if (screen != null && screen.nodes.any { purchase.containsMatchIn(it.label) && isNear(it, tapPoint.x, tapPoint.y) }) {
                return RiskAssessment(Risk.CONSEQUENTIAL, "The tap is next to a purchase element.")
            }
        }
        val t = targetText ?: return RiskAssessment(Risk.ROUTINE, "Navigation")
        return when {
            purchase.containsMatchIn(t) -> RiskAssessment(Risk.CONSEQUENTIAL, "This looks like a purchase: \"$t\"")
            destructive.containsMatchIn(t) -> RiskAssessment(Risk.CONSEQUENTIAL, "This may delete or reset something: \"$t\"")
            sending.containsMatchIn(t) -> RiskAssessment(Risk.CONSEQUENTIAL, "This may send or publish content: \"$t\"")
            security.containsMatchIn(t) -> RiskAssessment(Risk.CONSEQUENTIAL, "This touches security or account settings: \"$t\"")
            else -> RiskAssessment(Risk.ROUTINE, "Navigation")
        }
    }

    private fun nodeAt(s: ScreenSnapshot, x: Int, y: Int): UiNode? = s.nodes
        .filter { x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom && (it.text != null || it.contentDescription != null) }
        .minByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top) }

    private fun isNear(n: UiNode, x: Int, y: Int): Boolean =
        x in (n.bounds.left - 40)..(n.bounds.right + 40) && y in (n.bounds.top - 40)..(n.bounds.bottom + 40)
}

/** Named exclusive resources so parallel tasks never fight over the screen, mic or speaker. */
class ResourceLocks {
    enum class Resource { SCREEN, MICROPHONE, SPEAKER }
    private val locks = Resource.values().associateWith { Mutex() }
    suspend fun <T> withResource(resource: Resource, block: suspend () -> T): T = locks.getValue(resource).withLock { block() }
    fun isBusy(resource: Resource): Boolean = locks.getValue(resource).isLocked
}

/** Record of every action, for the task log the user can review. */
data class ActionRecord(
    val taskId: String,
    val action: String,
    val risk: Risk,
    val approved: Boolean?,
    val outcome: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * The ONLY path from Ciel's intelligence to the device. Enforces, in order:
 * emergency stop → risk policy → user confirmation → execution → audit record.
 */
class GuardedDevice(
    private val device: DeviceController,
    private val stop: EmergencyStop,
    private val gate: ConfirmationGate,
    private val audit: (ActionRecord) -> Unit,
) {
    val isAvailable: Boolean get() = device.isAvailable

    suspend fun observe(generation: Long, screenshot: Boolean = false): ScreenSnapshot {
        stop.checkNotStopped(generation)
        return device.observe(screenshot)
    }

    suspend fun installedApps(): Map<String, String> = device.installedApps()

    suspend fun perform(taskId: String, generation: Long, action: DeviceAction, screen: ScreenSnapshot?): ActionOutcome {
        stop.checkNotStopped(generation)
        val assessment = ActionPolicy.assess(action, screen)
        when (assessment.risk) {
            Risk.FORBIDDEN -> {
                audit(ActionRecord(taskId, action.describe(), assessment.risk, false, "Blocked: ${assessment.reason}"))
                return ActionOutcome.Failed("Blocked: ${assessment.reason}")
            }
            Risk.CONSEQUENTIAL -> {
                val approved = gate.confirm(ConfirmationRequest("Confirmation required", assessment.reason, assessment.risk, action.describe()))
                stop.checkNotStopped(generation) // a stop while the dialog was open wins
                if (!approved) {
                    audit(ActionRecord(taskId, action.describe(), assessment.risk, false, "Declined by user"))
                    return ActionOutcome.Failed("The user declined: ${assessment.reason}")
                }
            }
            Risk.ROUTINE -> Unit
        }
        val outcome = device.perform(action)
        audit(ActionRecord(taskId, action.describe(), assessment.risk, if (assessment.risk == Risk.CONSEQUENTIAL) true else null,
            when (outcome) { is ActionOutcome.Done -> "Done"; is ActionOutcome.Failed -> "Failed: ${outcome.reason}"; is ActionOutcome.Unsupported -> "Unsupported: ${outcome.reason}" }))
        return outcome
    }
}
