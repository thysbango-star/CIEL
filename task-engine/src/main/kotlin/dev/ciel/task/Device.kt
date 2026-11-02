package dev.ciel.task

/** Rectangle in screen pixels. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/** A simplified accessibility node. */
data class UiNode(
    val id: Int,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
) {
    val label: String get() = listOfNotNull(text, contentDescription).joinToString(" / ").ifBlank { viewId ?: className ?: "node" }
}

/** What Ciel can perceive of the screen at a moment in time. */
data class ScreenSnapshot(
    val packageName: String?,
    val activityName: String? = null,
    val nodes: List<UiNode> = emptyList(),
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    /** PNG/JPEG base64, only when the user granted screenshot capability. */
    val screenshotBase64: String? = null,
    val screenshotMime: String = "image/jpeg",
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 64-bit perceptual hash of the screen image (8×8 average hash), when a screenshot was taken.
     * Games draw everything themselves, so the accessibility tree is empty; this is how Ciel can
     * tell that a game screen changed after a tap.
     */
    val visualHash: Long? = null,
) {
    /** Number of differing hash bits (0 = identical image, 64 = completely different), or null. */
    fun visualDistance(other: ScreenSnapshot): Int? {
        val a = visualHash ?: return null
        val b = other.visualHash ?: return null
        return java.lang.Long.bitCount(a xor b)
    }

    /** True when the screen offers no structure (typical for games): only pixels, plus OCR text. */
    val isCanvasOnly: Boolean get() = nodes.none { (it.clickable || it.editable || it.scrollable) && it.className != OCR_CLASS }

    /**
     * A structural fingerprint of the screen, used to detect "same screen as before" and
     * interface changes after app updates. Ignores volatile text such as numbers and timers.
     */
    fun signature(): String {
        val parts = nodes.filter { it.clickable || it.editable || it.scrollable }
            .map { (it.viewId ?: it.className ?: "") + ":" + (it.text ?: it.contentDescription ?: "").replace(Regex("[0-9:.,%]+"), "#").take(24) }
            .sorted()
        val structural = (packageName ?: "?") + "|" + parts.joinToString(",").hashCode().toUInt().toString(16)
        // Canvas-only screens with no readable text: fall back to a coarse image fingerprint
        // (top 16 bits of the hash) so different game screens are told apart.
        return if (parts.isEmpty() && visualHash != null) structural + "|v" + (visualHash ushr 48).toString(16) else structural
    }

    /** Compact text rendering for the language model. */
    fun describe(maxNodes: Int = 120): String = buildString {
        append("App: ").append(packageName ?: "unknown")
        activityName?.let { append(" / ").append(it) }
        append("\nScreen: ${screenWidth}x$screenHeight")
        if (isCanvasOnly) append(" (drawn by the app itself: few or no accessible elements; texts marked 'seen' were read from the image)")
        append("\nElements:\n")
        nodes.filter { !it.bounds.isEmpty && (it.text != null || it.contentDescription != null || it.clickable || it.editable) }
            .take(maxNodes)
            .forEach { n ->
                append("[#${n.id}] ")
                append(n.label.take(80))
                val flags = listOfNotNull(
                    "seen".takeIf { n.className == OCR_CLASS },
                    "clickable".takeIf { n.clickable && n.className != OCR_CLASS }, "editable".takeIf { n.editable },
                    "scrollable".takeIf { n.scrollable }, n.checked?.let { if (it) "checked" else "unchecked" },
                )
                if (flags.isNotEmpty()) append(" (").append(flags.joinToString(",")).append(")")
                append(" @").append(n.bounds.centerX).append(',').append(n.bounds.centerY)
                append('\n')
            }
    }
}

/** className given to text that was read from the screenshot (OCR), not from accessibility. */
const val OCR_CLASS = "ocr-text"

enum class GlobalActionType { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** Every action Ciel can take on the device. The model never controls Android directly. */
sealed class DeviceAction {
    abstract fun describe(): String

    data class OpenApp(val packageName: String) : DeviceAction() { override fun describe() = "Open app $packageName" }
    data class ClickNode(val nodeId: Int, val expectedLabel: String? = null) : DeviceAction() {
        override fun describe() = "Tap element #$nodeId${expectedLabel?.let { " ($it)" } ?: ""}"
    }
    data class ClickText(val text: String) : DeviceAction() { override fun describe() = "Tap \"$text\"" }
    data class TapAt(val x: Int, val y: Int) : DeviceAction() { override fun describe() = "Tap at $x,$y" }
    /**
     * Tap at a position given as fractions of the screen (0..1). Used for games and screenshots:
     * models locate things in an image far more reliably as fractions than as device pixels.
     */
    data class TapRel(val fx: Float, val fy: Float) : DeviceAction() {
        override fun describe() = "Tap at ${(fx * 100).toInt()}%,${(fy * 100).toInt()}% of the screen"
        fun toPixels(w: Int, h: Int) = TapAt((fx.coerceIn(0f, 1f) * w).toInt(), (fy.coerceIn(0f, 1f) * h).toInt())
    }
    data class LongPressAt(val x: Int, val y: Int) : DeviceAction() { override fun describe() = "Long-press at $x,$y" }
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long = 300) : DeviceAction() {
        override fun describe() = "Swipe $x1,$y1 → $x2,$y2"
    }
    data class TypeText(val nodeId: Int?, val text: String) : DeviceAction() {
        override fun describe() = "Type into ${nodeId?.let { "#$it" } ?: "focused field"}: \"${text.take(40)}\""
    }
    data class Scroll(val direction: ScrollDirection, val nodeId: Int? = null) : DeviceAction() { override fun describe() = "Scroll ${direction.name.lowercase()}" }
    data class Global(val type: GlobalActionType) : DeviceAction() { override fun describe() = "Press ${type.name.lowercase()}" }
    data class Wait(val millis: Long) : DeviceAction() { override fun describe() = "Wait ${millis}ms" }
    data class OpenUrl(val url: String) : DeviceAction() { override fun describe() = "Open $url" }
    data class OpenSettings(val screen: String) : DeviceAction() { override fun describe() = "Open settings: $screen" }
}

sealed class ActionOutcome {
    object Done : ActionOutcome()
    data class Failed(val reason: String) : ActionOutcome()
    data class Unsupported(val reason: String) : ActionOutcome()
}

/**
 * Implemented on Android by the accessibility service (and intents for app launching).
 * Tests use a fake. All implementations must be side-effect free in [observe].
 */
interface DeviceController {
    val isAvailable: Boolean
    suspend fun observe(includeScreenshot: Boolean = false): ScreenSnapshot
    suspend fun perform(action: DeviceAction): ActionOutcome
    /** Installed launchable apps: label -> package. */
    suspend fun installedApps(): Map<String, String>
}
