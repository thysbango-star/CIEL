package dev.ciel.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.ciel.app.CielApplication
import dev.ciel.app.R
import dev.ciel.app.platform.Notifications
import dev.ciel.engine.CielState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Emergency stop from notification actions. */
class StopActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CielApplication.graph.emergencyStop.trigger("notification action")
    }
}

/** Colour for each interface state (Part 3). Shared by the overlay and the Compose UI. */
object StateColors {
    fun of(state: CielState): Int = when (state) {
        CielState.IDLE -> Color.rgb(92, 225, 255)
        CielState.LISTENING -> Color.rgb(140, 240, 255)
        CielState.PROCESSING -> Color.rgb(110, 170, 255)
        CielState.ANALYSING -> Color.rgb(160, 130, 255)
        CielState.RESEARCHING -> Color.rgb(90, 200, 220)
        CielState.SPEAKING -> Color.rgb(190, 245, 255)
        CielState.EXECUTING -> Color.rgb(255, 196, 92)
        CielState.COMPLETED -> Color.rgb(110, 240, 170)
        CielState.WARNING -> Color.rgb(255, 170, 70)
        CielState.ERROR -> Color.rgb(255, 90, 110)
    }
}

/** The compact animated emblem: two counter-rotating rune rings around a glowing core. */
class OrbView(context: Context) : View(context) {
    var state: CielState = CielState.IDLE
        set(value) { field = value; invalidate() }
    private var angle = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val c = StateColors.of(state)
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.92f
        val speed = when (state) { CielState.PROCESSING, CielState.ANALYSING, CielState.RESEARCHING, CielState.EXECUTING -> 3f; CielState.LISTENING -> 2f; else -> 1f }
        val a = angle * speed
        val pulse = if (state == CielState.LISTENING || state == CielState.SPEAKING) (0.85f + 0.15f * sin(Math.toRadians(a * 4.0)).toFloat()) else 1f
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(230, 5, 11, 24), Color.argb(255, 5, 11, 24)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        ring.color = c; ring.strokeWidth = r * 0.06f; ring.alpha = 255
        canvas.drawCircle(cx, cy, r * 0.9f, ring)
        // Rune ticks on the rotating outer ring.
        ring.strokeWidth = r * 0.04f
        for (i in 0 until 12) {
            val t = Math.toRadians((a + i * 30).toDouble())
            val r1 = r * 0.72f; val r2 = r * (if (i % 3 == 0) 0.84f else 0.79f)
            canvas.drawLine(cx + (r1 * cos(t)).toFloat(), cy + (r1 * sin(t)).toFloat(), cx + (r2 * cos(t)).toFloat(), cy + (r2 * sin(t)).toFloat(), ring)
        }
        // Counter-rotating inner hexagon.
        ring.alpha = 170
        val path = android.graphics.Path()
        for (i in 0..6) {
            val t = Math.toRadians((-a * 1.5 + i * 60))
            val x = cx + (r * 0.55f * cos(t)).toFloat(); val y = cy + (r * 0.55f * sin(t)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, ring)
        // Core.
        fill.shader = RadialGradient(cx, cy, r * 0.32f * pulse, intArrayOf(Color.WHITE, c, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.32f * pulse, fill)
    }
}

/**
 * Floating Ciel (Mode B). Requires "Display over other apps".
 * Gestures on the orb: tap = expand/collapse · double-tap = EMERGENCY STOP · long-press = open Ciel ·
 * drag = move.
 */
class FloatingCielService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        Notifications.ensureChannels(this)
        val hide = PendingIntent.getService(this, 3, Intent(this, FloatingCielService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 4, Intent(this, StopActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifications.SERVICES)
            .setSmallIcon(R.drawable.ic_ciel_mono).setContentTitle("Floating Ciel is on")
            .setContentText("Double-tap the orb for an emergency stop.").setOngoing(true)
            .addAction(0, "Hide", hide).addAction(0, "Emergency stop", stop).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 42, n, type)
        if (root == null) show()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val graph = CielApplication.graph

        val orb = OrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt())
            contentDescription = "Ciel. Tap to expand, double-tap for emergency stop, long-press to open."
        }
        val text = TextView(this).apply { setTextColor(Color.rgb(220, 248, 255)); textSize = 14f; maxLines = 7 }
        val confirmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
            this.text = label; setTextColor(Color.WHITE); isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(color) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 6, 6, 6) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("🎙 Speak", Color.rgb(30, 90, 130)) { graph.voiceCoordinator.listen(continuous = false) })
            addView(button("Open", Color.rgb(30, 60, 100)) { openApp() })
            addView(button("■ STOP", Color.rgb(190, 30, 50)) { graph.emergencyStop.trigger("floating stop") })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.argb(235, 8, 18, 38)); setStroke((1 * dp).toInt(), Color.argb(200, 92, 225, 255)) }
            layoutParams = LinearLayout.LayoutParams((280 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).toInt() }
            addView(text); addView(confirmBox); addView(buttons)
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(orb); addView(card) }
        root = container

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * dp).toInt(); y = (220 * dp).toInt() }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { card.visibility = if (card.visibility == View.VISIBLE) View.GONE else View.VISIBLE; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                graph.emergencyStop.trigger("double-tap on floating Ciel")
                text.text = "Affirmative. Everything is stopped."; card.visibility = View.VISIBLE
                return true
            }
            override fun onLongPress(e: MotionEvent) { openApp() }
        })
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        orb.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = ev.rawX; touchY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    if (dragging || dx * dx + dy * dy > 400) {
                        dragging = true
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(container, params) }
                    }
                }
            }
            if (!dragging) detector.onTouchEvent(ev)
            true
        }

        wm.addView(container, params)

        scope.launch {
            combine(graph.conversation.state, graph.conversation.streaming, graph.conversation.lastReply) { s, live, last -> Triple(s, live, last) }
                .collect { (s, live, last) ->
                    orb.state = s
                    text.text = when {
                        s == CielState.LISTENING -> "Listening…"
                        live.isNotBlank() -> live.takeLast(400)
                        s == CielState.PROCESSING || s == CielState.ANALYSING -> "Analysing…"
                        s == CielState.RESEARCHING -> "Researching…"
                        s == CielState.EXECUTING -> "Executing…"
                        else -> last?.take(400) ?: "Ready, Master."
                    }
                }
        }
        scope.launch {
            graph.confirmations.pending.collect { list ->
                confirmBox.removeAllViews()
                val p = list.firstOrNull()
                if (p == null) { confirmBox.visibility = View.GONE; return@collect }
                card.visibility = View.VISIBLE; confirmBox.visibility = View.VISIBLE
                confirmBox.addView(TextView(this@FloatingCielService).apply {
                    setTextColor(Color.rgb(255, 210, 150)); textSize = 13f
                    this.text = "${p.request.title}\n${p.request.detail}"
                })
                confirmBox.addView(LinearLayout(this@FloatingCielService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("Approve", Color.rgb(30, 120, 80)) { graph.confirmations.answer(p.id, true) })
                    addView(button("Decline", Color.rgb(120, 40, 50)) { graph.confirmations.answer(p.id, false) })
                })
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, dev.ciel.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_HIDE = "dev.ciel.floating.HIDE"
        fun start(context: Context) = context.startForegroundService(Intent(context, FloatingCielService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, FloatingCielService::class.java).setAction(ACTION_HIDE))
    }
}
