package dev.ciel.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.ciel.app.CielApplication
import dev.ciel.app.overlay.FloatingCielService
import dev.ciel.app.platform.CielNotificationListener
import dev.ciel.app.security.KeyVault
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.go
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.voice.CielVoice
import dev.ciel.app.voice.VoskModels
import dev.ciel.app.voice.WakeWordService
import dev.ciel.research.SearchProviderKind
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavHostController) {
    val graph = CielApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val wakeStatus by WakeWordService.status.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) } // refreshes permission-dependent text
    fun update(t: (dev.ciel.app.data.AppSettings) -> dev.ciel.app.data.AppSettings) { scope.launch { graph.settings.update(t) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CielPanel(title = "Quick access") {
            Row { TextButton(onClick = { nav.go("providers") }) { Text("AI providers") }; TextButton(onClick = { nav.go("permissions") }) { Text("Permissions") }; TextButton(onClick = { nav.go("memory") }) { Text("Memory") } }
            Row { TextButton(onClick = { nav.go("skills") }) { Text("Skills") }; TextButton(onClick = { nav.go("games") }) { Text("Games") } }
        }

        CielPanel(title = "Personality") {
            var address by remember(s.engine.persona.formOfAddress) { mutableStateOf(s.engine.persona.formOfAddress) }
            OutlinedTextField(address, { address = it }, label = { Text("How Ciel addresses you") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var language by remember(s.engine.persona.language) { mutableStateOf(s.engine.persona.language) }
            OutlinedTextField(language, { language = it }, label = { Text("Reply language") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { update { it.copy(engine = it.engine.copy(persona = it.engine.persona.copy(formOfAddress = address.trim(), language = language.trim().ifBlank { "English" }))) } }) { Text("Save") }
            Toggle("Use \"Notice / Answer / Report\" markers", s.engine.persona.useSystemMarkers) { v -> update { it.copy(engine = it.engine.copy(persona = it.engine.persona.copy(useSystemMarkers = v))) } }
            Toggle("Stream replies as they are written", s.engine.streaming) { v -> update { it.copy(engine = it.engine.copy(streaming = v)) } }
        }

        CielPanel(title = "Voice") {
            val voices = remember(tick) { graph.voice.voices() }
            var showVoices by remember { mutableStateOf(false) }
            Text("Voice: ${s.voice.voiceName ?: "automatic (female voice preferred)"}", color = CielColors.Text)
            TextButton(onClick = { showVoices = true }) { Text("Choose voice (${voices.size} installed)") }
            if (showVoices) AlertDialog(onDismissRequest = { showVoices = false }, title = { Text("Choose Ciel's voice") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Voices come from your phone's text-to-speech engine. Install more in Android Settings → Text-to-speech.", color = CielColors.Muted)
                    voices.forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.name + if (CielVoice.looksFemale(v)) " ♀" else "", Modifier.weight(1f), color = if (v.name == s.voice.voiceName) CielColors.Cyan else CielColors.Text)
                            TextButton(onClick = { graph.voice.preview(v) }) { Text("Play") }
                            TextButton(onClick = { update { it.copy(voice = it.voice.copy(voiceName = v.name)) }; showVoices = false }) { Text("Use") }
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { showVoices = false }) { Text("Close") } })
            Text("Speed ${"%.2f".format(s.voice.speechRate)}", color = CielColors.Muted)
            Slider(s.voice.speechRate, { v -> update { it.copy(voice = it.voice.copy(speechRate = v)) } }, valueRange = 0.6f..1.6f)
            Text("Pitch ${"%.2f".format(s.voice.pitch)}", color = CielColors.Muted)
            Slider(s.voice.pitch, { v -> update { it.copy(voice = it.voice.copy(pitch = v)) } }, valueRange = 0.7f..1.4f)
            Toggle("Speak replies to voice requests", s.voice.speakResponses) { v -> update { it.copy(voice = it.voice.copy(speakResponses = v)) } }
            Toggle("Continuous conversation (listen again after replying)", s.voice.continuousConversation) { v -> update { it.copy(voice = it.voice.copy(continuousConversation = v)) } }
        }

        CielPanel(title = "Activation") {
            Text("Most reliable: make Ciel your default digital assistant, then use your phone's assistant gesture (long-press power/home or corner swipe). " +
                "Also available: the \"Ask Ciel\" Quick Settings tile and Floating Ciel.", color = CielColors.Muted)
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text("Set default assistant") }
            Text("Wake word \"Ciel\" (experimental): $wakeStatus", color = CielColors.Text)
            Text("Runs offline on this phone, shows a permanent notification, uses extra battery, and must be switched on from inside the app after each reboot (Android 14 rule).", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            var progress by remember { mutableStateOf<Float?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            if (!VoskModels.isInstalled(context) || progress != null) {
                OutlinedButton(enabled = progress == null, onClick = {
                    progress = 0f; error = null
                    scope.launch {
                        try { VoskModels.download(context) { p -> progress = p } ; tick++ } catch (e: Exception) { error = e.message }
                        progress = null
                    }
                }) { Text("Download wake-word model (~40 MB from alphacephei.com)") }
                progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                error?.let { Text(it, color = CielColors.Danger) }
            } else {
                Toggle("Listen for \"Ciel\"", s.wakeWordEnabled) { v ->
                    update { it.copy(wakeWordEnabled = v) }
                    if (v) {
                        if (graph.capabilities.has(Manifest.permission.RECORD_AUDIO)) WakeWordService.start(context) else (context as? dev.ciel.app.ui.MainActivity)?.listen()
                    } else WakeWordService.stop(context)
                }
                var phrases by remember(s.wakePhrases) { mutableStateOf(s.wakePhrases.joinToString(", ")) }
                OutlinedTextField(phrases, { phrases = it }, label = { Text("Accepted spellings (comma-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { update { it.copy(wakePhrases = phrases.split(',').map { p -> p.trim().lowercase() }.filter { p -> p.isNotEmpty() }) } }) { Text("Save (restart listening to apply)") }
            }
            Toggle("Floating Ciel over other apps", s.floatingEnabled) { v ->
                if (v && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    update { it.copy(floatingEnabled = v) }
                    if (v) FloatingCielService.start(context) else FloatingCielService.stop(context)
                }
            }
        }

        CielPanel(title = "Research") {
            Text("Web search needs a search API. Brave and Tavily need a key from their websites; SearXNG needs your own instance with JSON output enabled.", color = CielColors.Muted)
            Row { SearchProviderKind.values().forEach { k -> TextButton(onClick = { update { it.copy(searchKind = k) } }) { Text(k.label + if (s.searchKind == k) " ✓" else "") } } }
            if (s.searchKind?.needsKey == true) {
                var key by remember { mutableStateOf("") }
                OutlinedTextField(key, { key = it.trim() }, label = { Text(if (graph.vault.has(KeyVault.searchKeyName())) "API key saved — enter to replace" else "Search API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(enabled = key.isNotBlank(), onClick = { graph.vault.put(KeyVault.searchKeyName(), key); key = "" }) { Text("Save key") }
            }
            if (s.searchKind == SearchProviderKind.SEARXNG) {
                var url by remember(s.searchBaseUrl) { mutableStateOf(s.searchBaseUrl ?: "") }
                OutlinedTextField(url, { url = it.trim() }, label = { Text("SearXNG base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { update { it.copy(searchBaseUrl = url) } }) { Text("Save URL") }
            }
        }

        CielPanel(title = "Messaging") {
            Text("SMS is sent directly after your approval. WhatsApp, Telegram and email open the app with the message filled in — you tap send. " +
                "Discord posts go to channel webhooks you add here (Server settings → Integrations → Webhooks).", color = CielColors.Muted)
            s.discordWebhooks.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#$name", Modifier.weight(1f), color = CielColors.Text)
                    TextButton(onClick = { graph.vault.delete(KeyVault.discordWebhookName(name)); update { it.copy(discordWebhooks = it.discordWebhooks - name) } }) { Text("Remove") }
                }
            }
            var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
            OutlinedTextField(name, { name = it.trim() }, label = { Text("Channel name (what you'll call it)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Webhook URL") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            TextButton(enabled = name.isNotBlank() && url.startsWith("https://discord"), onClick = {
                graph.vault.put(KeyVault.discordWebhookName(name), url)
                update { it.copy(discordWebhooks = (it.discordWebhooks + name).distinct()) }
                name = ""; url = ""
            }) { Text("Add webhook") }
        }

        CielPanel(title = "Automation & interface") {
            Toggle("Send screenshots to vision models during automation", s.allowScreenshots) { v -> update { it.copy(allowScreenshots = v) } }
            Text("Off: only the on-screen text/element list is sent. Games usually need screenshots.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            Toggle("Reduce motion (saves battery)", s.reducedMotion) { v -> update { it.copy(reducedMotion = v) } }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Battery optimisation settings") }
        }

        CielPanel(title = "Privacy") {
            var confirm by remember { mutableStateOf<String?>(null) }
            OutlinedButton(onClick = { scope.launch { graph.conversation.clear() } }) { Text("Clear conversation history") }
            OutlinedButton(onClick = { scope.launch { graph.db.tasks().clearTasks(); graph.db.tasks().clearLog() } }) { Text("Clear task history & action log") }
            OutlinedButton(onClick = { CielNotificationListener.clear() }) { Text("Forget read notifications") }
            OutlinedButton(onClick = { confirm = "keys" }) { Text("Delete all API keys", color = CielColors.Danger) }
            if (confirm == "keys") AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete every stored key?") },
                text = { Text("All provider, search and webhook secrets and the Keystore key that protects them are destroyed.") },
                confirmButton = { TextButton(onClick = { graph.vault.wipeAll(); confirm = null }) { Text("Delete", color = CielColors.Danger) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
            Text("Nothing is sent anywhere except to the AI, search and messaging services you configure. No analytics. No backups.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Permission dashboard (Part 8): each permission, why it's needed, and its live status. */
@Composable
fun PermissionsScreen() {
    val graph = CielApplication.graph
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; graph.capabilitiesChanged() }
    val caps = graph.capabilities
    LaunchedEffect(refresh) { graph.capabilitiesChanged() }

    data class Entry(val name: String, val why: String, val granted: Boolean, val action: () -> Unit)
    val entries = remember(refresh) {
        listOf(
            Entry("Microphone", "Voice commands and the \"Ciel\" wake word. Only used while the mic indicator is on.", caps.has(Manifest.permission.RECORD_AUDIO)) { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            Entry("Notifications", "Reminders, task reports and confirmation requests.", Build.VERSION.SDK_INT < 33 || caps.has(Manifest.permission.POST_NOTIFICATIONS)) {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            Entry("Screen control (Accessibility)", "Lets Ciel read the screen and tap/type/scroll for tasks you request, and powers the STOP button. Purchases, sending, deleting and security changes always need your approval.", caps.accessibilityEnabled()) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            Entry("Display over other apps", "Floating Ciel and its emergency stop above other apps.", Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
            Entry("Notification access", "Summarise and reply to incoming messages. Read only when you ask; kept in memory, never stored.", caps.notificationAccess()) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            Entry("Contacts", "Find the right recipient when you say \"message John\". Ambiguous names are always confirmed with you.", caps.has(Manifest.permission.READ_CONTACTS)) { launcher.launch(Manifest.permission.READ_CONTACTS) },
            Entry("Send SMS", "Send text messages after you approve the exact text and recipient.", caps.has(Manifest.permission.SEND_SMS)) { launcher.launch(Manifest.permission.SEND_SMS) },
            Entry("Exact alarms", "Reminders fire on time instead of a few minutes late.", dev.ciel.skills.Capability.EXACT_ALARMS in caps.granted()) {
                if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CielPanel(title = "Permissions") {
            Text("Ciel asks for each permission only when a feature needs it. Nothing here is required for basic conversation.", color = CielColors.Muted)
            TextButton(onClick = { refresh++ }) { Text("Refresh status") }
        }
        entries.forEach { e ->
            CielPanel(title = e.name, accent = if (e.granted) CielColors.Green.copy(alpha = 0.6f) else CielColors.PanelBorder) {
                Text(e.why, color = CielColors.Text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.granted) "Granted" else "Not granted", Modifier.weight(1f), color = if (e.granted) CielColors.Green else CielColors.Amber)
                    if (!e.granted) Button(onClick = e.action) { Text("Grant") } else TextButton(onClick = e.action) { Text("Manage") }
                }
            }
        }
    }
}
