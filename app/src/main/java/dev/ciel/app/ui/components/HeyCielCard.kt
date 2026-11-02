package dev.ciel.app.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.overlay.VoiceOverlay
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.voice.HeyCielState
import dev.ciel.app.voice.WakeWordService
import kotlinx.coroutines.launch

/** One-tap "Hey Ciel": permission → one-time model download → listening. */
@Composable
fun HeyCielCard(modifier: Modifier = Modifier) {
    val graph = CielApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val setup by graph.heyCiel.state.collectAsStateWithLifecycle()
    val status by WakeWordService.status.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        graph.capabilitiesChanged()
        scope.launch { graph.heyCiel.enable(context) }
    }
    val on = settings.wakeWordEnabled

    CielPanel(modifier, title = "Hey Ciel") {
        Text(
            if (on) "Ciel is listening for \"Hey Ciel\" — status: ${status.lowercase()}."
            else "Say \"Hey Ciel\" from any screen and she answers, like Gemini. Detection runs entirely on your phone; nothing is sent anywhere until she has heard her name.",
            color = CielColors.Text,
        )
        when (val st = setup) {
            is HeyCielState.Downloading -> {
                Text("Downloading the offline voice model (one time, about 40 MB)…", color = CielColors.Cyan, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            HeyCielState.Starting -> Text("Starting…", color = CielColors.Cyan)
            is HeyCielState.Failed -> Text(st.message, color = CielColors.Danger)
            HeyCielState.Idle -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!on) Button(enabled = setup !is HeyCielState.Downloading, onClick = {
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissions.launch(wanted.toTypedArray())
            }) { Text("Enable \"Hey Ciel\"") }
            else OutlinedButton(onClick = { scope.launch { graph.heyCiel.disable(context) } }) { Text("Turn off") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text("Default assistant") }
        }
        if (on && !VoiceOverlay.canShow(context)) Text(
            "Tip: allow \"Display over other apps\" (or Ciel's screen control) so her voice panel appears over other apps.",
            color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
        Text(
            "Android rules: a notification stays visible while she listens, battery savers can pause her, and after a reboot open Ciel once to resume. " +
                "Setting Ciel as the default assistant also lets the long-press/assistant gesture wake her.",
            color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

