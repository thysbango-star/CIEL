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
import dev.ciel.app.design.CielDesign
import dev.ciel.app.design.DesignPalette
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

/**
 * The compact crystal used by Floating Ciel and the voice panel: Ciel's core image (tinted for
 * the theme) inside rings matching the layout. Runs at full frame rate only while Ciel is active;
 * idle redraws ~15 times a second to save battery.
 */
class OrbView(context: Context) : View(context) {
    var state: CielState = CielState.IDLE
        set(value) { field = value; updateAnimation(); invalidate() }
    var design: CielDesign = CielDesign.GREAT_SAGE
        set(value) { field = value; invalidate() }
    var palette: DesignPalette = DesignPalette.resolve(dev.ciel.app.design.Appearance())
        set(value) { field = value; invalidate() }
    var theme: dev.ciel.app.design.ThemeStyle = dev.ciel.app.design.ThemeStyle.AZURE_CALM
        set(value) {
            field = value
            corePaint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(dev.ciel.app.design.CrystalTint.matrix(value)))
            invalidate()
        }
    private var angle = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(dev.ciel.app.design.CrystalTint.matrix(dev.ciel.app.design.ThemeStyle.AZURE_CALM)))
    }
    private val core: android.graphics.Bitmap? = coreBitmap(context)
    private val dst = android.graphics.RectF()
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }
    private var attached = false

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; updateAnimation() }
    override fun onDetachedFromWindow() { attached = false; animator.cancel(); super.onDetachedFromWindow() }

    private fun updateAnimation() {
        if (!attached) return
        if (state == CielState.IDLE) { if (animator.isRunning) animator.pause() }
        else if (animator.isPaused) animator.resume() else if (!animator.isStarted) animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        val c = if (state == CielState.IDLE) palette.accent else StateColors.of(state)
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.95f
        if (state == CielState.IDLE && attached) { angle = (angle + 0.6f) % 360f; postInvalidateDelayed(66) }
        val speed = when (state) { CielState.PROCESSING, CielState.ANALYSING, CielState.RESEARCHING, CielState.EXECUTING -> 3f; CielState.LISTENING -> 2f; else -> 1f }
        val a = angle * speed
        val pulse = if (state == CielState.LISTENING || state == CielState.SPEAKING) (0.92f + 0.08f * sin(Math.toRadians(a * 4.0)).toFloat()) else 1f
        // Soft aura
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(110, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        ring.color = c; ring.strokeWidth = r * 0.035f
        when (design) {
            CielDesign.RAPHAEL -> { ring.alpha = 220; polygon(canvas, cx, cy, r * 0.92f, 6, a * 0.5) }
            CielDesign.CIEL -> {
                ring.alpha = 220; canvas.drawCircle(cx, cy, r * 0.92f, ring)
                for (i in 0 until 12) {
                    val t = Math.toRadians((a + i * 30).toDouble())
                    canvas.drawLine(cx + (r * 0.8f * cos(t)).toFloat(), cy + (r * 0.8f * sin(t)).toFloat(), cx + (r * 0.9f * cos(t)).toFloat(), cy + (r * 0.9f * sin(t)).toFloat(), ring)
                }
            }
            else -> {
                // Two tilted orbital ellipses like the main crystal.
                ring.alpha = 170
                for ((i, tilt) in listOf(-18f, 40f).withIndex()) {
                    canvas.save(); canvas.rotate(tilt + a * 0.1f * (if (i == 0) 1 else -1), cx, cy)
                    canvas.drawOval(cx - r * 0.95f, cy - r * 0.3f, cx + r * 0.95f, cy + r * 0.3f, ring)
                    canvas.restore()
                }
            }
        }
        // Crystal core image
        val coreR = r * 0.62f * pulse
        if (core != null) {
            canvas.save(); canvas.rotate(a * 0.3f, cx, cy)
            dst.set(cx - coreR, cy - coreR, cx + coreR, cy + coreR)
            canvas.drawBitmap(core, null, dst, corePaint)
            canvas.restore()
        } else {
            fill.shader = RadialGradient(cx, cy, coreR, intArrayOf(Color.WHITE, c, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, coreR, fill)
        }
    }

    private fun polygon(canvas: Canvas, cx: Float, cy: Float, radius: Float, sides: Int, rotationDeg: Double) {
        val path = android.graphics.Path()
        for (i in 0..sides) {
            val t = Math.toRadians(rotationDeg + i * 360.0 / sides)
            val x = cx + (radius * cos(t)).toFloat(); val y = cy + (radius * sin(t)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, ring)
    }

    companion object {
        @Volatile private var cached: android.graphics.Bitmap? = null
        /** Decoded once (downsampled to 256 px) and shared by every orb. */
        fun coreBitmap(context: Context): android.graphics.Bitmap? = cached ?: runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ciel_core, opts)
        }.getOrNull().also { cached = it }
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

        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val appearance = graph.settings.current.appearance
        val orb = OrbView(this).apply {
            design = appearance.design
            theme = appearance.theme
            palette = DesignPalette.resolve(appearance, night)
            val px = (appearance.floatingSize.dp * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(px, px)
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
