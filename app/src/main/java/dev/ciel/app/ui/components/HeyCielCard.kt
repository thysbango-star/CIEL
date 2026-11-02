package dev.ciel.app.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.overlay.VoiceOverlay
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.voice.HeyCielState
import dev.ciel.app.voice.WakeHealth
import dev.ciel.app.voice.WakeSensitivity
import dev.ciel.app.voice.WakeWordService
import kotlinx.coroutines.launch

/** "Hey Ciel": one-tap setup, real service health, sensitivity, and the Test Hey Ciel diagnostic. */
@Composable
fun HeyCielCard(modifier: Modifier = Modifier, showAdvanced: Boolean = true) {
    val graph = CielApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val setup by graph.heyCiel.state.collectAsStateWithLifecycle()
    val health by WakeWordService.health.collectAsStateWithLifecycle()
    val heard by WakeWordService.lastHeard.collectAsStateWithLifecycle()
    val checks by graph.heyCielDiagnostics.checks.collectAsStateWithLifecycle()
    val testing by graph.heyCielDiagnostics.running.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        graph.capabilitiesChanged()
        scope.launch { graph.heyCiel.enable(context) }
    }
    val on = settings.wakeWordEnabled

    CielPanel(modifier, title = "Hey Ciel") {
        val (color, text) = when {
            !on -> CielColors.Muted to "Off. Say \"Hey Ciel\" from any screen once it's on — detection runs entirely on your phone."
            health is WakeHealth.Listening -> CielColors.Green to "Listening for \"Hey Ciel\"${if (settings.wakeSingleWord) " and \"Ciel\"" else ""}."
            health is WakeHealth.Problem -> CielColors.Danger to (health as WakeHealth.Problem).reason
            else -> CielColors.Amber to "Status: ${health.label}"
        }
        Text(text, color = color)
        heard?.let { Text("Last heard: \"$it\"", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall) }
        when (val st = setup) {
            is HeyCielState.Downloading -> {
                Text("Downloading the offline voice model (one time, about 40 MB)…", color = CielColors.Cyan, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            HeyCielState.Starting -> Text("Starting…", color = CielColors.Cyan)
            is HeyCielState.Failed -> Text(st.message, color = CielColors.Danger)
            HeyCielState.Idle -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (!on || health is WakeHealth.Problem) Button(enabled = setup !is HeyCielState.Downloading, onClick = {
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissions.launch(wanted.toTypedArray())
            }) { Text(if (on) "Restart \"Hey Ciel\"" else "Enable \"Hey Ciel\"") }
            else OutlinedButton(onClick = { scope.launch { graph.heyCiel.disable(context) } }) { Text("Turn off") }
            OutlinedButton(enabled = !testing && on, onClick = { scope.launch { graph.heyCielDiagnostics.run(context) } }) { Text(if (testing) "Testing…" else "Test Hey Ciel") }
        }

        checks.forEach { c ->
            val mark = when (c.ok) { true -> "✓"; false -> "✗"; null -> "•" }
            val col = when (c.ok) { true -> CielColors.Green; false -> CielColors.Danger; null -> CielColors.Amber }
            Column(Modifier.padding(top = 4.dp)) {
                Text("$mark ${c.name}", color = col, style = MaterialTheme.typography.titleMedium)
                Text(c.detail, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (showAdvanced) {
            Text("Sensitivity", color = CielColors.Text, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WakeSensitivity.values().forEach { sv ->
                    FilterChip(selected = settings.wakeSensitivity == sv, label = { Text(sv.label) }, onClick = {
                        scope.launch { graph.settings.update { it.copy(wakeSensitivity = sv) }; if (on) WakeWordService.reload(context) }
                    })
                }
            }
            Text(settings.wakeSensitivity.detail, color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Also wake on \"Ciel\" alone", Modifier.weight(1f), color = CielColors.Text)
                Switch(settings.wakeSingleWord, { v -> scope.launch { graph.settings.update { it.copy(wakeSingleWord = v) }; if (on) WakeWordService.reload(context) } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text("Default assistant") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Battery settings") }
            }
            if (on && !VoiceOverlay.canShow(context)) Text(
                "Tip: allow \"Display over other apps\" so Ciel's voice panel appears over other apps.",
                color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(
                "How to use: say \"Hey Ciel\", wait for the chime, then speak. Android shows a notification while she listens; after a restart, open Ciel once to resume. " +
                    "The offline model doesn't know the word \"Ciel\", so similar-sounding words are accepted too.",
                color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
