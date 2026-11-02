package dev.ciel.app.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ciel.app.CielApplication
import dev.ciel.app.R
import dev.ciel.app.overlay.StopActionReceiver
import dev.ciel.app.platform.Notifications
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonArray
import dev.ciel.core.json.JsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** How eagerly the wake word triggers. */
enum class WakeSensitivity(val label: String, val detail: String) {
    LOW("Low", "Only a complete \"Hey Ciel\" sentence on its own"),
    NORMAL("Normal", "\"Hey Ciel\" at the start of what you say"),
    HIGH("High", "Reacts while you're still speaking; more false triggers"),
}

/** Truthful service health — "Listening" is only shown when audio capture really started. */
sealed class WakeHealth(val label: String) {
    object Off : WakeHealth("Off")
    object Starting : WakeHealth("Starting")
    object Listening : WakeHealth("Listening")
    object Handling : WakeHealth("Heard you")
    object Paused : WakeHealth("Paused")
    data class Problem(val reason: String) : WakeHealth("Problem")
}

/**
 * Offline wake-word detection ("Hey Ciel" / "Ciel") with Vosk and a restricted grammar, so
 * audio never leaves the phone while waiting.
 *
 * Fixes over 0.2: the model loads on a background thread (no main-thread stall that could get
 * the service killed), health reflects real capture state, errors trigger automatic restarts,
 * and everything the engine hears is exposed for the "Test Hey Ciel" diagnostic.
 *
 * Android rules respected: visible foreground service of type microphone, started while Ciel is
 * on screen (Android 14 forbids starting it from the background or at boot), with Stop actions.
 */
class WakeWordService : Service() {
    private var speech: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var phrases: List<String> = emptyList()
    private var sensitivity = WakeSensitivity.NORMAL
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var restarts = 0
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { setHealth(WakeHealth.Off); stopSelf(); return START_NOT_STICKY }
        Notifications.ensureChannels(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setHealth(WakeHealth.Problem("Microphone permission was removed. Open Ciel → Settings → Voice to allow it again."))
            stopSelf(); return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), type)
        } catch (e: Exception) {
            // Typically Android 14+ refusing a background (re)start of a microphone service.
            setHealth(WakeHealth.Problem("Android paused background listening. Open Ciel once to resume \"Hey Ciel\"."))
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> { releaseMic(); setHealth(WakeHealth.Paused); return START_STICKY }
            ACTION_RESUME -> { resumeMic(); return START_STICKY }
            ACTION_RELOAD -> { releaseMic(); recognizer?.close(); recognizer = null }
        }
        if (recognizer == null) loadAndStart() else if (speech == null) resumeMic()
        return START_STICKY
    }

    /** Loads the model off the main thread, then starts capture on the main thread. */
    private fun loadAndStart() {
        if (!VoskModels.isInstalled(this)) {
            setHealth(WakeHealth.Problem("The offline voice model isn't downloaded. Open Ciel → Settings → Voice → Enable \"Hey Ciel\"."))
            stopSelf(); return
        }
        setHealth(WakeHealth.Starting)
        val s = CielApplication.graph.settings.current
        sensitivity = s.wakeSensitivity
        phrases = s.wakePhrases.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
            .filter { s.wakeSingleWord || it.contains(' ') }
            .ifEmpty { listOf("hey ciel", "hey seal") }
        val dir = VoskModels.modelDir(this)
        loader.execute {
            try {
                val m = model ?: Model(dir.absolutePath)
                // Grammar: wake phrases, "<wake> stop", and an unknown-word catch-all.
                val grammar = JsonArray((phrases + phrases.map { "$it stop" } + "[unk]").map { JsonString(it) }).toString()
                val rec = Recognizer(m, 16000f, grammar)
                main.post { model = m; recognizer = rec; resumeMic() }
            } catch (e: Throwable) {
                main.post { setHealth(WakeHealth.Problem("The voice model couldn't load (${e.message}). Try re-downloading it in Settings → Voice.")) }
            }
        }
    }

    /** Starts capturing audio for the wake word. */
    private fun resumeMic() {
        val rec = recognizer ?: return
        if (speech != null) { setHealth(WakeHealth.Listening); return }
        try {
            val s = SpeechService(rec, 16000f)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = textOf(hypothesis, "partial") ?: return
                    _lastHeard.value = text
                    if (sensitivity == WakeSensitivity.HIGH) handle(text, partial = true)
                }
                override fun onResult(hypothesis: String?) { textOf(hypothesis, "text")?.let { _lastHeard.value = it; handle(it, partial = false) } }
                override fun onFinalResult(hypothesis: String?) { textOf(hypothesis, "text")?.let { handle(it, partial = false) } }
                override fun onError(exception: Exception?) { onEngineError(exception?.message ?: "unknown error") }
                override fun onTimeout() = Unit
            })
            speech = s
            restarts = 0
            setHealth(WakeHealth.Listening)
        } catch (e: Exception) {
            onEngineError("microphone unavailable: ${e.message}")
        }
    }

    /** Recovery: release and retry with back-off; after 3 failures report the problem. */
    private fun onEngineError(reason: String) {
        releaseMic()
        if (restarts < 3) {
            restarts++
            setHealth(WakeHealth.Starting)
            main.postDelayed({ if (recognizer != null && speech == null && health.value !is WakeHealth.Handling) resumeMic() }, 2000L * restarts)
        } else {
            setHealth(WakeHealth.Problem("The microphone keeps failing ($reason). Another app may be using it. Tap Test Hey Ciel."))
        }
    }

    /** Fully stops audio capture so the command recogniser (or another app) can use the mic. */
    private fun releaseMic() {
        speech?.stop(); speech?.shutdown(); speech = null
    }

    private fun textOf(hypothesis: String?, field: String): String? {
        val raw = Json.parseOrNull(hypothesis ?: return null)?.get(field)?.string?.trim()?.lowercase() ?: return null
        return raw.split(" ").filter { it.isNotBlank() && it != "[unk]" }.joinToString(" ").ifEmpty { null }
    }

    private fun matches(text: String, phrase: String): Boolean = when (sensitivity) {
        WakeSensitivity.LOW -> text == phrase
        WakeSensitivity.NORMAL -> text == phrase || text.startsWith("$phrase ")
        WakeSensitivity.HIGH -> text == phrase || text.startsWith("$phrase ") || text.contains(" $phrase")
    }

    private fun handle(text: String, partial: Boolean) {
        // Diagnostic mode: report what was heard instead of activating.
        testListener?.let { l -> if (phrases.any { matches(text, it) }) { l(text); return } }
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 2500) return // one activation per utterance
        val graph = CielApplication.graph
        when {
            phrases.any { matches(text, "$it stop") } -> { lastTrigger = now; graph.emergencyStop.trigger("voice: Ciel, stop") }
            phrases.any { matches(text, it) } -> {
                if (partial && text.split(" ").size > 4) return
                lastTrigger = now
                releaseMic()
                setHealth(WakeHealth.Handling)
                graph.voiceCoordinator.onWakeWord {
                    startService(Intent(this, WakeWordService::class.java).setAction(ACTION_RESUME))
                }
            }
        }
    }

    /**
     * Offline dictation with the same Vosk model (no grammar). Used when Android's speech
     * recogniser refuses to work while another app is in the foreground. Main thread only.
     */
    fun dictate(onText: (String?) -> Unit) {
        val m = model ?: return onText(null)
        val rec = try { Recognizer(m, 16000f) } catch (e: Exception) { return onText(null) }
        var finished = false
        var svc: SpeechService? = null
        val handler = Handler(mainLooper)
        fun finish(t: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            runCatching { svc?.stop(); svc?.shutdown() }
            runCatching { rec.close() }
            onText(t?.takeIf { it.isNotBlank() })
        }
        try {
            svc = SpeechService(rec, 16000f).also {
                it.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) = Unit
                    override fun onResult(hypothesis: String?) { textOf(hypothesis, "text")?.let { t -> finish(t) } }
                    override fun onFinalResult(hypothesis: String?) { finish(textOf(hypothesis, "text")) }
                    override fun onError(exception: Exception?) { finish(null) }
                    override fun onTimeout() { finish(null) }
                })
            }
            handler.postDelayed({ finish(null) }, 10_000)
        } catch (e: Exception) {
            finish(null)
        }
    }

    private fun setHealth(h: WakeHealth) {
        _health.value = h
        _status.value = h.label
        if (h !is WakeHealth.Off) runCatching {
            val text = when (h) {
                is WakeHealth.Problem -> h.reason
                WakeHealth.Listening -> "Listening for \"Hey Ciel\" — audio stays on this phone"
                else -> h.label
            }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val emergency = PendingIntent.getBroadcast(this, 2, Intent(this, StopActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, dev.ciel.app.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifications.SERVICES)
            .setSmallIcon(R.drawable.ic_ciel_mono)
            .setContentTitle("Ciel")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .addAction(0, "Emergency stop", emergency)
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        releaseMic()
        recognizer?.close(); recognizer = null
        model?.close(); model = null
        loader.shutdownNow()
        if (_health.value !is WakeHealth.Problem) { _health.value = WakeHealth.Off; _status.value = "Off" }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.ciel.wake.STOP"
        const val ACTION_PAUSE = "dev.ciel.wake.PAUSE"
        const val ACTION_RESUME = "dev.ciel.wake.RESUME"
        /** Re-reads phrases/sensitivity from settings. */
        const val ACTION_RELOAD = "dev.ciel.wake.RELOAD"
        private const val NOTIF_ID = 41

        @Volatile var instance: WakeWordService? = null
            private set
        /** When set, a detected wake phrase is reported here instead of activating Ciel. */
        @Volatile var testListener: ((String) -> Unit)? = null

        private val _status = MutableStateFlow("Off")
        val status: StateFlow<String> = _status
        private val _health = MutableStateFlow<WakeHealth>(WakeHealth.Off)
        val health: StateFlow<WakeHealth> = _health
        private val _lastHeard = MutableStateFlow<String?>(null)
        /** The most recent words the wake-word engine recognised (for diagnostics). */
        val lastHeard: StateFlow<String?> = _lastHeard

        fun isRunning() = instance != null && _health.value is WakeHealth.Listening

        fun start(context: Context) = context.startForegroundService(Intent(context, WakeWordService::class.java))
        fun reload(context: Context) = context.startForegroundService(Intent(context, WakeWordService::class.java).setAction(ACTION_RELOAD))
        fun stop(context: Context) = context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP))
        fun pause(context: Context) = context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE))
        fun resume(context: Context) = context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME))
    }
}

/** Downloads and unpacks the small English Vosk model (≈40 MB) on the user's request. */
object VoskModels {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun modelDir(context: Context) = File(context.filesDir, "vosk-model")
    fun isInstalled(context: Context) = File(modelDir(context), "am").exists() || File(modelDir(context), "conf").exists()

    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "vosk.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 41_000_000L
            conn.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var read = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); read += n; onProgress((read.toFloat() / total).coerceAtMost(0.99f)) }
            } }
        } finally { conn.disconnect() }
        val target = modelDir(context); target.deleteRecursively(); target.mkdirs()
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // Strip the top-level folder and guard against path traversal.
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(target, rel)
                if (!f.canonicalPath.startsWith(target.canonicalPath + File.separator)) throw SecurityException("Bad zip entry")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.delete()
        onProgress(1f)
        if (!isInstalled(context)) throw IllegalStateException("The model archive was not in the expected format.")
    }
}
