package dev.ciel.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.ciel.app.CielApplication
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CielApplication.graph.capabilitiesChanged()
        if (granted && pendingListen) CielApplication.graph.voiceCoordinator.listen()
        pendingListen = false
    }
    private var pendingListen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CielApp(onListen = ::listen) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in system settings.
        val graph = CielApplication.graph
        graph.capabilitiesChanged()
        // Background services may only (re)start while Ciel is in the foreground (Android 14+).
        val s = graph.settings.current
        if (s.wakeWordEnabled && dev.ciel.app.voice.VoskModels.isInstalled(this) &&
            graph.capabilities.has(Manifest.permission.RECORD_AUDIO) &&
            dev.ciel.app.voice.WakeWordService.status.value !in setOf("Listening", "Paused", "Heard you")) {
            dev.ciel.app.voice.WakeWordService.start(this)
        }
        if (s.floatingEnabled && android.provider.Settings.canDrawOverlays(this)) dev.ciel.app.overlay.FloatingCielService.start(this)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            intent.removeExtra(EXTRA_LISTEN)
            lifecycleScope.launch { listen() }
        }
    }

    /** Microphone permission is requested the first time the user actually asks to speak. */
    fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CielApplication.graph.voiceCoordinator.listen()
        } else {
            pendingListen = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object { const val EXTRA_LISTEN = "dev.ciel.LISTEN" }
}
