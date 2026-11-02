package dev.ciel.app.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import java.util.zip.ZipInputStream

/**
 * Offline wake-word detection for "Ciel" using Vosk with a restricted grammar, so audio never
 * leaves the phone while waiting for the wake word.
 *
 * Android rules this respects (and that make "always listening" impossible to promise):
 *  - It runs as a visible foreground service with a persistent notification; the system mic
 *    indicator stays on while it listens.
 *  - Android 14+ only allows starting a microphone foreground service while Ciel is in the
 *    foreground, so it is started from the app and cannot auto-start after a reboot.
 *  - Battery optimisation or the user may stop it; the notification has a Stop action.
 */
class WakeWordService : Service() {
    private var speech: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var phrases: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Listening for \"Hey Ciel\""), type)
        } catch (e: Exception) {
            _status.value = "Android blocked background listening: open Ciel and enable it again."
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) { releaseMic(); _status.value = "Paused"; return START_STICKY }
        if (intent?.action == ACTION_RESUME) { resumeMic(); return START_STICKY }
        if (recognizer == null) startListening() else if (speech == null) resumeMic()
        return START_STICKY
    }

    private fun startListening() {
        val dir = VoskModels.modelDir(this)
        if (!VoskModels.isInstalled(this)) {
            _status.value = "Wake-word model not downloaded (Settings → Voice)."
            stopSelf(); return
        }
        try {
            val m = Model(dir.absolutePath)
            model = m
            phrases = CielApplication.graph.settings.current.wakePhrases.map { it.lowercase() }
            // Grammar: wake phrases, "<wake> stop", and an unknown-word catch-all.
            val grammar = JsonArray((phrases + phrases.map { "$it stop" } + "[unk]").map { JsonString(it) }).toString()
            recognizer = Recognizer(m, 16000f, grammar)
            resumeMic()
        } catch (e: Exception) {
            _status.value = "Could not start wake-word listening: ${e.message}"
            stopSelf()
        }
    }

    /** Starts capturing audio for the wake word. */
    private fun resumeMic() {
        val rec = recognizer ?: return
        if (speech != null) return
        try {
            val s = SpeechService(rec, 16000f)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = Unit
                override fun onResult(hypothesis: String?) { handle(hypothesis, phrases) }
                override fun onFinalResult(hypothesis: String?) { handle(hypothesis, phrases) }
                override fun onError(exception: Exception?) { _status.value = "Wake-word error: ${exception?.message}" }
                override fun onTimeout() = Unit
            })
            speech = s
            _status.value = "Listening"
        } catch (e: Exception) {
            _status.value = "Microphone unavailable: ${e.message}"
        }
    }

    /** Fully stops audio capture so the command recogniser (or another app) can use the mic. */
    private fun releaseMic() {
        speech?.stop(); speech?.shutdown(); speech = null
    }

    private fun handle(hypothesis: String?, phrases: List<String>) {
        val raw = Json.parseOrNull(hypothesis ?: return)?.get("text")?.string?.trim()?.lowercase() ?: return
        val text = raw.split(" ").filter { it.isNotBlank() && it != "[unk]" }.joinToString(" ")
        if (text.isEmpty()) return
        val graph = CielApplication.graph
        when {
            phrases.any { text == "$it stop" || text.startsWith("$it stop ") } -> graph.emergencyStop.trigger("voice: Ciel, stop")
            phrases.any { text == it || text.startsWith("$it ") } -> {
                // Hand the microphone to the command recogniser, then take it back.
                releaseMic()
                _status.value = "Heard you"
                graph.voiceCoordinator.onWakeWord {
                    startService(Intent(this, WakeWordService::class.java).setAction(ACTION_RESUME))
                }
            }
        }
    }

    /**
     * Offline dictation with the same Vosk model (no grammar). Used when Android's speech
     * recogniser refuses to work while another app is in the foreground. Must be called on the
     * main thread while the wake-word capture is released.
     */
    fun dictate(onText: (String?) -> Unit) {
        val m = model ?: return onText(null)
        val rec = try { Recognizer(m, 16000f) } catch (e: Exception) { return onText(null) }
        var finished = false
        var svc: SpeechService? = null
        val handler = android.os.Handler(mainLooper)
        fun finish(t: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            runCatching { svc?.stop(); svc?.shutdown() }
            runCatching { rec.close() }
            onText(t?.takeIf { it.isNotBlank() })
        }
        fun textOf(h: String?) = Json.parseOrNull(h ?: "")?.get("text")?.string?.trim()
        try {
            svc = SpeechService(rec, 16000f).also {
                it.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) = Unit
                    override fun onResult(hypothesis: String?) { textOf(hypothesis)?.takeIf { t -> t.isNotEmpty() }?.let { t -> finish(t) } }
                    override fun onFinalResult(hypothesis: String?) { finish(textOf(hypothesis)) }
                    override fun onError(exception: Exception?) { finish(null) }
                    override fun onTimeout() { finish(null) }
                })
            }
            handler.postDelayed({ finish(null) }, 10_000)
        } catch (e: Exception) {
            finish(null)
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val emergency = PendingIntent.getBroadcast(this, 2, Intent(this, StopActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, dev.ciel.app.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifications.SERVICES)
            .setSmallIcon(R.drawable.ic_ciel_mono)
            .setContentTitle("Ciel")
            .setContentText(text + " — audio is processed on this device only")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .addAction(0, "Emergency stop", emergency)
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseMic()
        recognizer?.close(); recognizer = null
        model?.close(); model = null
        if (_status.value == "Listening" || _status.value == "Paused") _status.value = "Off"
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.ciel.wake.STOP"
        const val ACTION_PAUSE = "dev.ciel.wake.PAUSE"
        const val ACTION_RESUME = "dev.ciel.wake.RESUME"
        private const val NOTIF_ID = 41
        @Volatile var instance: WakeWordService? = null
            private set
        private val _status = MutableStateFlow("Off")
        val status: StateFlow<String> = _status

        fun start(context: Context) = context.startForegroundService(Intent(context, WakeWordService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP))
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
