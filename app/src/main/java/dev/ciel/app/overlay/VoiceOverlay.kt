package dev.ciel.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.ciel.app.CielApplication
import dev.ciel.app.design.DesignPalette
import dev.ciel.app.platform.CielAccessibilityService
import dev.ciel.app.voice.ListenState
import dev.ciel.engine.CielState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The panel that slides up over any app when you say "Hey Ciel" (like Gemini's overlay):
 * live transcription, Ciel's state and reply, plus Stop and Open buttons.
 *
 * It is drawn by the accessibility service when that is enabled (no extra permission), or as a
 * normal overlay when "Display over other apps" is granted. With neither, Ciel still listens and
 * answers by voice — only the panel is skipped.
 */
object VoiceOverlay {
    private var root: View? = null
    private var wm: WindowManager? = null
    private var scope: CoroutineScope? = null
    private var hideJob: Job? = null

    fun canShow(context: Context) = CielAccessibilityService.instance != null || Settings.canDrawOverlays(context)

    /** Must be called on the main thread. */
    fun show(context: Context) {
        hideJob?.cancel()
        if (root != null) return
        val a11y = CielAccessibilityService.instance
        val host: Context = a11y ?: context.applicationContext
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else if (Settings.canDrawOverlays(context)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else return
        val graph = CielApplication.graph
        val appearance = graph.settings.current.appearance
        val design = appearance.design
        val dp = host.resources.displayMetrics.density
        val night = (host.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val palette = DesignPalette.resolve(appearance, night)

        val orb = OrbView(host).apply {
            this.design = design
            this.palette = palette
            this.theme = appearance.theme
            layoutParams = LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt())
            state = CielState.LISTENING
        }
        val title = TextView(host).apply { setTextColor(palette.accent); textSize = 11f; letterSpacing = 0.15f; text = "LISTENING" }
        val body = TextView(host).apply { setTextColor(palette.text); textSize = 16f; maxLines = 6; text = "Hey Ciel…" }
        val textCol = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(title); addView(body)
        }
        fun chip(label: String, color: Int, onClick: () -> Unit) = TextView(host).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * dp; setColor(color) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() }
        }
        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, (10 * dp).toInt(), 0, 0)
            addView(chip("Open Ciel", Color.argb(255, 30, 70, 110)) {
                host.startActivity(Intent(host, dev.ciel.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); hide()
            })
            addView(chip("■ Stop", Color.argb(255, 190, 30, 50)) { graph.emergencyStop.trigger("voice panel stop"); hide() })
            addView(chip("✕", Color.argb(255, 40, 50, 70)) { graph.voice.stop(); graph.speech.cancel(); hide() })
        }
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(orb); addView(textCol) }
        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(palette.panel); setStroke((1 * dp).toInt(), palette.border) }
            addView(row); addView(actions)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM; x = 0; y = (24 * dp).toInt(); horizontalMargin = 0.03f }
        val manager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { manager.addView(panel, lp) } catch (e: Exception) { return }
        panel.translationY = 200 * dp
        panel.animate().translationY(0f).setDuration(220).start()
        root = panel; wm = manager

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(graph.conversation.state, graph.conversation.streaming, graph.conversation.lastReply, graph.speech.state) { st, live, last, listen ->
                arrayOf<Any?>(st, live, last, listen)
            }.collect { arr ->
                val st = arr[0] as CielState; val live = arr[1] as String; val last = arr[2] as String?; val listen = arr[3] as ListenState
                orb.state = st
                title.text = when (st) {
                    CielState.LISTENING -> "LISTENING"; CielState.SPEAKING -> "CIEL"; CielState.EXECUTING -> "EXECUTING"
                    CielState.RESEARCHING -> "RESEARCHING"; CielState.ERROR -> "NOTICE"; CielState.IDLE, CielState.COMPLETED -> "CIEL"
                    else -> "ANALYSING"
                }
                body.text = when {
                    listen is ListenState.Listening -> listen.partial.ifBlank { "I'm listening." }
                    live.isNotBlank() -> live.takeLast(500)
                    st == CielState.PROCESSING || st == CielState.ANALYSING -> "…"
                    else -> last?.take(500) ?: body.text
                }
            }
        }
    }

    /** Hides after [delayMs] unless shown again in the meantime. */
    fun hideSoon(delayMs: Long = 4000) {
        hideJob?.cancel()
        hideJob = CoroutineScope(Dispatchers.Main).launch { delay(delayMs); hide() }
    }

    fun hide() {
        hideJob?.cancel()
        scope?.cancel(); scope = null
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null; wm = null
    }
}
