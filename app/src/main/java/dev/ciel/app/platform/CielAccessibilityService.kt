package dev.ciel.app.platform

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import androidx.annotation.RequiresApi
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.ciel.app.CielApplication
import dev.ciel.task.ActionOutcome
import dev.ciel.task.Bounds
import dev.ciel.task.DeviceAction
import dev.ciel.task.GlobalActionType
import dev.ciel.task.ScreenSnapshot
import dev.ciel.task.ScrollDirection
import dev.ciel.task.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Ciel's hands and eyes, enabled explicitly by the user in Settings → Accessibility.
 * It only acts when the task engine asks it to; it does not record or upload anything on its own.
 *
 * Emergency stop surfaces provided here (they exist whenever automation is possible):
 *  - a floating STOP pill shown on top of every app while an automation task runs,
 *  - the system Accessibility button / shortcut, which triggers the stop.
 */
class CielAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var lastActivity: String? = null
    /** Node handles from the most recent observation, keyed by the ids given to the model. */
    private val nodeMap = HashMap<Int, AccessibilityNodeInfo>()
    private var stopPill: TextView? = null
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                CielApplication.graph.emergencyStop.trigger("accessibility button")
            }
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(cb)
        buttonCallback = cb

        val graph = CielApplication.graph
        // Show the STOP pill whenever an automation task (screen control) is running.
        scope.launch {
            graph.tasks.tasks
                .map { list -> list.any { it.isActive && (it.kind == "execute" || it.kind == "game") } }
                .distinctUntilChanged()
                .collect { running -> if (running) showStopPill() else hideStopPill() }
        }
        graph.capabilitiesChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.let { if (!it.startsWith("android.widget")) lastActivity = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hideStopPill()
        buttonCallback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        scope.cancel()
        if (instance === this) instance = null
        CielApplication.graphOrNull?.capabilitiesChanged()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ observation

    suspend fun observe(includeScreenshot: Boolean): ScreenSnapshot = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow
        val nodes = ArrayList<UiNode>()
        synchronized(nodeMap) {
            nodeMap.clear()
            if (root != null) collectNodes(root, nodes, 0)
        }
        val metrics = resources.displayMetrics
        val shot = if (includeScreenshot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) screenshot() else null
        ScreenSnapshot(
            packageName = root?.packageName?.toString(),
            activityName = lastActivity,
            nodes = nodes,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            screenshotBase64 = shot,
        )
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<UiNode>, depth: Int) {
        if (depth > 40 || out.size > 400) return
        if (!node.isVisibleToUser) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        val id = out.size + 1
        val text = node.text?.toString()?.take(200)?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.take(200)?.takeIf { it.isNotBlank() }
        val interesting = text != null || desc != null || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        if (interesting) {
            out += UiNode(
                id = id, text = text, contentDescription = desc,
                viewId = node.viewIdResourceName?.substringAfter(":id/"),
                className = node.className?.toString()?.substringAfterLast('.'),
                bounds = Bounds(r.left, r.top, r.right, r.bottom),
                clickable = node.isClickable, editable = node.isEditable, scrollable = node.isScrollable,
                checked = if (node.isCheckable) node.isChecked else null,
            )
            nodeMap[id] = node
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNodes(it, out, depth + 1) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshot(): String? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val encoded = runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return@runCatching null
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    val scale = 1024f / maxOf(soft.width, soft.height)
                    val small = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true) else soft
                    val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }.getOrNull()
                if (cont.isActive) cont.resume(encoded)
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }

    // ------------------------------------------------------------------ actions

    suspend fun perform(action: DeviceAction): ActionOutcome = withContext(Dispatchers.Main) {
        when (action) {
            is DeviceAction.ClickNode -> {
                val node = synchronized(nodeMap) { nodeMap[action.nodeId] } ?: return@withContext ActionOutcome.Failed("Element #${action.nodeId} is not on the current screen.")
                clickNode(node)
            }
            is DeviceAction.ClickText -> {
                val root = rootInActiveWindow ?: return@withContext ActionOutcome.Failed("No active window.")
                val match = root.findAccessibilityNodeInfosByText(action.text).firstOrNull { it.isVisibleToUser }
                    ?: return@withContext ActionOutcome.Failed("No visible element shows \"${action.text}\".")
                clickNode(match)
            }
            is DeviceAction.TapAt -> gesture(tapPath(action.x, action.y), 60)
            is DeviceAction.LongPressAt -> gesture(tapPath(action.x, action.y), 700)
            is DeviceAction.Swipe -> gesture(Path().apply { moveTo(action.x1.toFloat(), action.y1.toFloat()); lineTo(action.x2.toFloat(), action.y2.toFloat()) }, action.durationMs.coerceIn(50, 3000))
            is DeviceAction.TypeText -> {
                val target = action.nodeId?.let { synchronized(nodeMap) { nodeMap[it] } }
                    ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return@withContext ActionOutcome.Failed("No text field is focused.")
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) }
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ActionOutcome.Done else ActionOutcome.Failed("The field did not accept text.")
            }
            is DeviceAction.Scroll -> scroll(action)
            is DeviceAction.Global -> {
                val code = when (action.type) {
                    GlobalActionType.BACK -> GLOBAL_ACTION_BACK
                    GlobalActionType.HOME -> GLOBAL_ACTION_HOME
                    GlobalActionType.RECENTS -> GLOBAL_ACTION_RECENTS
                    GlobalActionType.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                    GlobalActionType.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                }
                if (performGlobalAction(code)) ActionOutcome.Done else ActionOutcome.Failed("System refused ${action.type}.")
            }
            is DeviceAction.Wait -> { kotlinx.coroutines.delay(action.millis); ActionOutcome.Done }
            // Launch-type actions are handled by AndroidDeviceController with intents.
            else -> ActionOutcome.Unsupported("Handled elsewhere")
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): ActionOutcome {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionOutcome.Done
        // Fallback: tap the centre of the element (works for custom-drawn views).
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return ActionOutcome.Failed("Element has no visible area.")
        val p = tapPath(r.centerX(), r.centerY())
        return if (dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null))
            ActionOutcome.Done else ActionOutcome.Failed("Tap was rejected.")
    }

    private suspend fun scroll(action: DeviceAction.Scroll): ActionOutcome {
        val target = action.nodeId?.let { synchronized(nodeMap) { nodeMap[it] } } ?: synchronized(nodeMap) { nodeMap.values.firstOrNull { it.isScrollable } }
        val forward = action.direction == ScrollDirection.DOWN || action.direction == ScrollDirection.RIGHT
        if (target != null && target.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return ActionOutcome.Done
        // Fallback swipe in the middle of the screen.
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f; val cy = m.heightPixels / 2f
        val dy = m.heightPixels * 0.3f; val dx = m.widthPixels * 0.3f
        val path = Path().apply {
            when (action.direction) {
                ScrollDirection.DOWN -> { moveTo(cx, cy + dy); lineTo(cx, cy - dy) }
                ScrollDirection.UP -> { moveTo(cx, cy - dy); lineTo(cx, cy + dy) }
                ScrollDirection.RIGHT -> { moveTo(cx + dx, cy); lineTo(cx - dx, cy) }
                ScrollDirection.LEFT -> { moveTo(cx - dx, cy); lineTo(cx + dx, cy) }
            }
        }
        return gesture(path, 350)
    }

    private fun tapPath(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private suspend fun gesture(path: Path, durationMs: Long): ActionOutcome = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val accepted = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(ActionOutcome.Done) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(ActionOutcome.Failed("Gesture was cancelled by the system.")) }
        }, null)
        if (!accepted && cont.isActive) cont.resume(ActionOutcome.Failed("Gesture was rejected."))
    }

    // ------------------------------------------------------------------ stop pill

    private fun showStopPill() {
        if (stopPill != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pill = TextView(this).apply {
            text = "■  STOP CIEL"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(48, 24, 48, 24)
            background = GradientDrawable().apply { cornerRadius = 80f; setColor(Color.argb(235, 200, 30, 50)); setStroke(3, Color.argb(255, 255, 160, 170)) }
            contentDescription = "Emergency stop: halt all of Ciel's automated actions"
            setOnClickListener { CielApplication.graph.emergencyStop.trigger("floating stop button") }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 120 }
        runCatching { wm.addView(pill, lp); stopPill = pill }
    }

    private fun hideStopPill() {
        val p = stopPill ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(p) }
        stopPill = null
    }

    companion object {
        @Volatile var instance: CielAccessibilityService? = null
            private set
    }
}
