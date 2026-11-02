package dev.ciel.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import dev.ciel.app.AppGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** One diagnostic line. ok = null means informational / waiting. */
data class DiagnosticCheck(val name: String, val ok: Boolean?, val detail: String)

/**
 * "Test Hey Ciel": checks every link in the chain and reports what actually happens, instead of
 * assuming. Permission → model → service health → microphone signal → wake-word recognition.
 */
class HeyCielDiagnostics(private val graph: AppGraph) {
    private val _checks = MutableStateFlow<List<DiagnosticCheck>>(emptyList())
    val checks: StateFlow<List<DiagnosticCheck>> = _checks
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private fun add(c: DiagnosticCheck) { _checks.value = _checks.value + c }
    private fun replaceLast(c: DiagnosticCheck) { _checks.value = _checks.value.dropLast(1) + c }

    /** Must be started while Ciel is on screen (it may start the listening service). */
    suspend fun run(context: Context) {
        if (_running.value) return
        _running.value = true
        _checks.value = emptyList()
        try {
            val mic = graph.capabilities.has(Manifest.permission.RECORD_AUDIO)
            add(DiagnosticCheck("Microphone permission", mic, if (mic) "Granted" else "Not granted — tap Enable \"Hey Ciel\" and allow the microphone."))
            if (!mic) return

            if (Build.VERSION.SDK_INT >= 33) {
                val n = graph.capabilities.has(Manifest.permission.POST_NOTIFICATIONS)
                add(DiagnosticCheck("Notification permission", n, if (n) "Granted" else "Not granted — the listening notification can't show; Android may stop the service sooner."))
            }

            val model = VoskModels.isInstalled(context)
            add(DiagnosticCheck("Offline voice model", model, if (model) "Installed" else "Missing — tap Enable \"Hey Ciel\" to download it (40 MB, once)."))
            if (!model) return

            val pm = context.getSystemService(PowerManager::class.java)
            val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
            add(DiagnosticCheck("Battery optimisation", if (exempt) true else null,
                if (exempt) "Ciel is exempt — Android won't pause listening to save battery."
                else "Ciel is optimised — some phones pause listening after a while. Use \"Battery settings\" to set Ciel to Unrestricted."))

            // Service must be running for the recognition test.
            if (WakeWordService.instance == null) {
                add(DiagnosticCheck("Listening service", null, "Starting…"))
                WakeWordService.start(context)
                withTimeoutOrNull(8000) { while (WakeWordService.health.value !is WakeHealth.Listening && WakeWordService.health.value !is WakeHealth.Problem) delay(200) }
                replaceLast(serviceCheck())
            } else add(serviceCheck())

            // Microphone signal: pause wake capture, record 1.5 s, resume.
            add(DiagnosticCheck("Microphone signal", null, "Listening for 1.5 s — say anything…"))
            WakeWordService.pause(context); delay(400)
            val peak = measurePeak(1500)
            WakeWordService.resume(context); delay(600)
            replaceLast(when {
                peak == null -> DiagnosticCheck("Microphone signal", false, "Couldn't open the microphone — another app may be using it (calls, recorders, other assistants).")
                peak < 300 -> DiagnosticCheck("Microphone signal", false, "Almost silent (level $peak). Check the mic isn't covered or muted.")
                else -> DiagnosticCheck("Microphone signal", true, "Sound detected (level $peak).")
            })

            if (WakeWordService.health.value !is WakeHealth.Listening) {
                add(DiagnosticCheck("Wake-word test", false, "Skipped — the listening service isn't running."))
                return
            }

            // Wake-word recognition.
            add(DiagnosticCheck("Wake-word test", null, "Say \"Hey Ciel\" now (8 s)…"))
            val heard = CompletableDeferred<String>()
            WakeWordService.testListener = { heard.complete(it) }
            val result = try { withTimeoutOrNull(8000) { heard.await() } } finally { WakeWordService.testListener = null }
            val last = WakeWordService.lastHeard.value
            replaceLast(if (result != null) DiagnosticCheck("Wake-word test", true, "Detected \"$result\". Hey Ciel works.")
                else DiagnosticCheck("Wake-word test", false,
                    "Not detected." + (last?.let { " The engine heard \"$it\" — try speaking a little slower, or set Sensitivity to High." }
                        ?: " Nothing was recognised — check the microphone signal above.")))
        } finally {
            _running.value = false
        }
    }

    private fun serviceCheck(): DiagnosticCheck = when (val h = WakeWordService.health.value) {
        is WakeHealth.Listening -> DiagnosticCheck("Listening service", true, "Running and capturing audio.")
        is WakeHealth.Problem -> DiagnosticCheck("Listening service", false, h.reason)
        else -> DiagnosticCheck("Listening service", false, "State: ${h.label}. It didn't reach Listening.")
    }

    @SuppressLint("MissingPermission") // checked at the start of run()
    private suspend fun measurePeak(millis: Long): Int? = withContext(Dispatchers.IO) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext null
        val rec = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) }
            catch (e: Exception) { return@withContext null }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            rec.startRecording()
            val buf = ShortArray(min)
            var peak = 0
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) return@withContext null
                for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
            }
            peak
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
