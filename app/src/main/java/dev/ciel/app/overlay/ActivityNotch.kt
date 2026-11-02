package dev.ciel.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ciel.app.CielApplication
import dev.ciel.app.design.DesignPalette
import dev.ciel.app.platform.CielAccessibilityService
import dev.ciel.engine.CielState
import dev.ciel.game.ChatLine
import dev.ciel.game.GameActivity
import dev.ciel.game.PendingQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The floating activity notch shown above a game while Ciel works (Part 9).
 *
 *  Collapsed: crystal + current action + "4/9" + attention dot.
 *  Expanded:  current task, current action, progress, status, a pending question with its
 *             options, and Pause/Resume · Stop · Ask Ciel · View task · Open Ciel.
 *
 * Everything shown comes from [dev.ciel.game.GameEngine.activity] — the running engine — never
 * from a timer or a guess. Shown as an accessibility overlay (no extra permission needed while
 * the accessibility service runs), else as an app overlay if "Display over other apps" is on.
 * During Ciel's own taps the notch lets touches pass through, so it never blocks the game.
 */
object ActivityNotch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: NotchView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var hideJob: Job? = null

    /** Starts following the game engine's live activity. Call once (AppGraph). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start(context: Context, graph: dev.ciel.app.AppGraph) {
        scope.launch {
            graph.gameEngine.activity.collectLatest { a ->
                if (a == null) { hide(); return@collectLatest }
                if (view == null) show(context.applicationContext)
                view?.render(a)
                hideJob?.cancel()
                if (a.status == GameActivity.Status.FINISHED || a.status == GameActivity.Status.FAILED) {
                    // Leave the result visible for a moment, then get out of the way.
                    hideJob = scope.launch { delay(if (a.needsAttention) 20_000 else 8_000); if (view?.expanded != true) graph.gameEngine.dismissActivity() }
                }
            }
        }
        scope.launch {
            graph.gameEngine.activity.flatMapLatest { a -> a?.let { graph.gameEngine.channel(it.taskId)?.questions } ?: flowOf(emptyList()) }
                .collect { q -> view?.renderQuestions(q) }
        }
        scope.launch {
            graph.gameEngine.activity.flatMapLatest { a -> a?.let { graph.gameEngine.channel(it.taskId)?.chat } ?: flowOf(emptyList()) }
                .collect { c -> view?.renderChat(c) }
        }
    }

    /** Screen area covered by the notch (excluded from on-screen text reading). */
    fun bounds(): Rect? {
        val v = view ?: return null
        if (!v.isAttachedToWindow) return null
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** While Ciel taps the game, touches pass through the notch. */
    fun passThrough(on: Boolean) {
        val v = view ?: return; val p = params ?: return
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun show(appContext: Context) {
        val service = CielAccessibilityService.instance
        val ctx: Context = service ?: appContext
        val type = when {
            service != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(appContext) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return // no way to draw over the game; the Tasks tab still shows progress
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = ctx.resources.displayMetrics.density
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((ctx.resources.displayMetrics.widthPixels - 260 * dp) / 2).toInt().coerceAtLeast(0)
            y = (36 * dp).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        val v = NotchView(ctx) { focusable -> setFocusable(focusable) }
        runCatching { manager.addView(v, p) }.onFailure { return }
        view = v; params = p; wm = manager
        v.alpha = 0f; v.translationY = -12 * dp
        v.animate().alpha(1f).translationY(0f).setDuration(260).start()
    }

    private fun setFocusable(on: Boolean) {
        val v = view ?: return; val p = params ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm?.updateViewLayout(v, p) }
    }

    internal fun move(dx: Int, dy: Int, startX: Int, startY: Int) {
        val v = view ?: return; val p = params ?: return
        val m = v.resources.displayMetrics
        p.x = (startX + dx).coerceIn(0, maxOf(0, m.widthPixels - v.width))
        p.y = (startY + dy).coerceIn((16 * m.density).toInt(), maxOf(0, m.heightPixels - v.height - (16 * m.density).toInt()))
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** Keeps the (possibly just expanded) notch on screen. */
    internal fun clamp() { val p = params ?: return; move(0, 0, p.x, p.y) }
    internal fun position(): Pair<Int, Int> = params?.let { it.x to it.y } ?: (0 to 0)

    private fun hide() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).translationY(-8f * v.resources.displayMetrics.density).setDuration(180).withEndAction {
            runCatching { wm?.removeView(v) }
        }.start()
        params = null
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
private class NotchView(context: Context, private val onNeedsKeyboard: (Boolean) -> Unit) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val graph = CielApplication.graph
    private val appearance = graph.settings.current.appearance
    private val palette = DesignPalette.resolve(appearance)
    private val textColor = Color.rgb(226, 242, 255)
    private val muted = Color.argb(200, 170, 196, 225)
    var expanded = false; private set
    private var activity: GameActivity? = null
    private var asking = false

    private val orb = OrbView(context).apply {
        design = appearance.design; theme = appearance.theme; palette = this@NotchView.palette
        layoutParams = LayoutParams((26 * dp).toInt(), (26 * dp).toInt())
    }
    private val actionText = TextView(context).apply {
        setTextColor(textColor); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        maxWidth = (200 * dp).toInt(); setPadding((8 * dp).toInt(), 0, (6 * dp).toInt(), 0)
    }
    private val progressText = TextView(context).apply { setTextColor(muted); textSize = 12f }
    private val dot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(255, 196, 92)) }
        layoutParams = LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply { marginStart = (6 * dp).toInt() }
        visibility = GONE
    }
    private val pill = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        addView(orb); addView(actionText); addView(progressText); addView(dot)
    }

    private fun label(t: String) = TextView(context).apply { text = t; setTextColor(muted); textSize = 11f; letterSpacing = 0.08f; setPadding(0, (6 * dp).toInt(), 0, 0) }
    private fun value() = TextView(context).apply { setTextColor(textColor); textSize = 14f }
    private val taskValue = value(); private val actionValue = value(); private val progressValue = value(); private val statusValue = value()
    private val questionBox = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val chatList = LinearLayout(context).apply { orientation = VERTICAL }
    private val chatScroll = ScrollView(context).apply {
        addView(chatList)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (150 * dp).toInt())
        isVerticalScrollBarEnabled = true
    }
    private val input = EditText(context).apply {
        hint = "Ask Ciel…"; setHintTextColor(muted); setTextColor(textColor); textSize = 14f; maxLines = 3
        imeOptions = EditorInfo.IME_ACTION_SEND; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.argb(60, 255, 255, 255)) }
        setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
    }
    private val askPanel = LinearLayout(context).apply {
        orientation = VERTICAL; visibility = GONE
        addView(chatScroll)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(input); addView(small("Send") { send() })
        })
    }
    private val pauseBtn = small("Pause") { togglePause() }
    private val panel = LinearLayout(context).apply {
        orientation = VERTICAL; visibility = GONE
        setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        addView(label("CURRENT TASK")); addView(taskValue)
        addView(label("CURRENT ACTION")); addView(actionValue)
        addView(label("PROGRESS")); addView(progressValue)
        addView(label("STATUS")); addView(statusValue)
        addView(questionBox)
        addView(row(pauseBtn, small("Stop", Color.rgb(200, 40, 60)) { graph.emergencyStop.trigger("activity notch stop") }, small("Ask Ciel") { toggleAsk() }))
        addView(row(small("View task") { graph.navigate("tasks") }, small("Open Ciel") { graph.navigate("home") }, small("Hide") { graph.gameEngine.dismissActivity(); collapse() }))
        addView(askPanel)
    }

    init {
        orientation = VERTICAL
        val screenW = resources.displayMetrics.widthPixels
        minimumWidth = (200 * dp).toInt()
        background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(DesignPalette.withAlpha(palette.surface, 225))
            setStroke((1 * dp).toInt(), DesignPalette.withAlpha(palette.accent, 170))
        }
        addView(pill); addView(panel)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Expanded width never exceeds the screen.
        panel.layoutParams = LayoutParams(minOf((300 * dp).toInt(), screenW - (24 * dp).toInt()), LayoutParams.WRAP_CONTENT)
        contentDescription = "Ciel activity. Tap to expand, drag to move."
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var dragging = false
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { val (x, y) = ActivityNotch.position(); sx = x; sy = y; tx = e.rawX; ty = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (dragging || dx * dx + dy * dy > 144 * dp) { dragging = true; ActivityNotch.move(dx.toInt(), dy.toInt(), sx, sy) }
                }
                MotionEvent.ACTION_UP -> if (!dragging) { if (expanded) collapse() else expand() }
            }
            true
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { ActivityNotch.clamp() } }
    }

    private fun small(text: String, color: Int = Color.argb(90, 92, 160, 255), onClick: () -> Unit) = Button(context).apply {
        this.text = text; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        minHeight = (40 * dp).toInt(); minimumHeight = (40 * dp).toInt()
        background = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(color) }
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt(), 0) }
    }
    private fun row(vararg v: View) = LinearLayout(context).apply { orientation = HORIZONTAL; v.forEach { addView(it) } }

    private fun animateLayout() = TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(220))
    fun expand() { animateLayout(); expanded = true; panel.visibility = VISIBLE }
    fun collapse() {
        animateLayout(); expanded = false; panel.visibility = GONE
        if (asking) toggleAsk()
    }

    private fun toggleAsk() {
        animateLayout()
        asking = !asking
        askPanel.visibility = if (asking) VISIBLE else GONE
        onNeedsKeyboard(asking)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (asking) { input.requestFocus(); post { imm.showSoftInput(input, 0) } } else imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        graph.messageGameTask(text)
    }

    private fun togglePause() {
        val a = activity ?: return
        if (a.status == GameActivity.Status.PAUSED) { graph.tasks.resume(a.taskId); graph.gameEngine.markPaused(false) }
        else { graph.tasks.pause(a.taskId); graph.gameEngine.markPaused(true) }
    }

    fun render(a: GameActivity) {
        activity = a
        val waiting = a.status == GameActivity.Status.WAITING
        orb.state = when (a.status) {
            GameActivity.Status.RUNNING -> CielState.EXECUTING
            GameActivity.Status.WAITING -> CielState.WARNING
            GameActivity.Status.PAUSED -> CielState.IDLE
            GameActivity.Status.FINISHED -> CielState.COMPLETED
            GameActivity.Status.FAILED -> CielState.WARNING
        }
        actionText.text = when (a.status) {
            GameActivity.Status.FINISHED -> "Done — ${a.title.lowercase()}"
            GameActivity.Status.FAILED -> "Finished — needs your attention"
            GameActivity.Status.PAUSED -> "Paused"
            else -> if (waiting) "Waiting for you" else a.currentAction.ifBlank { a.title } + "…"
        }
        progressText.text = if (a.stepCount > 0) "  ${minOf(a.stepIndex + 1, a.stepCount)}/${a.stepCount}" else ""
        dot.visibility = if (a.needsAttention) VISIBLE else GONE
        taskValue.text = "${a.title} · ${a.game}"
        actionValue.text = a.currentAction + (a.screen?.let { "\nScreen: $it" } ?: "")
        progressValue.text = "${a.progressText} — ${a.stepTitle}"
        statusValue.text = a.status.label
        pauseBtn.text = if (a.status == GameActivity.Status.PAUSED) "Resume" else "Pause"
        pauseBtn.isEnabled = a.status == GameActivity.Status.RUNNING || a.status == GameActivity.Status.WAITING || a.status == GameActivity.Status.PAUSED
    }

    fun renderQuestions(list: List<PendingQuestion>) {
        questionBox.removeAllViews()
        val q = list.firstOrNull()
        if (q == null) { questionBox.visibility = GONE; return }
        if (!expanded) expand()
        questionBox.visibility = VISIBLE
        questionBox.addView(label("CIEL NEEDS YOUR DECISION"))
        questionBox.addView(TextView(context).apply { text = q.question + "\n" + q.consequence; setTextColor(Color.rgb(255, 214, 160)); textSize = 14f })
        questionBox.addView(row(*q.options.mapIndexed { i, o ->
            small(o, if (i == 0) Color.argb(160, 40, 140, 90) else Color.argb(120, 120, 60, 70)) { graph.gameEngine.channel(activity?.taskId ?: return@small)?.answer(q.id, i) }
        }.toTypedArray()))
    }

    fun renderChat(lines: List<ChatLine>) {
        chatList.removeAllViews()
        lines.takeLast(20).forEach { l ->
            chatList.addView(TextView(context).apply {
                text = (if (l.fromUser) "You: " else "Ciel: ") + l.text
                setTextColor(if (l.fromUser) muted else textColor); textSize = 13f
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            })
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
