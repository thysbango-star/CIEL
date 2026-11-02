package dev.ciel.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.ciel.app.CielApplication
import dev.ciel.app.data.ConversationSummary
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.HeyCielCard
import dev.ciel.app.ui.go
import dev.ciel.app.ui.theme.CielColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Conversations: previous chats, open one, start a new one, delete. */
@Composable
fun ChatsScreen(onOpened: () -> Unit) {
    val graph = CielApplication.graph
    val conv = graph.conversation
    val scope = rememberCoroutineScope()
    val list by conv.conversations.collectAsStateWithLifecycle(emptyList<ConversationSummary>())
    val current by conv.currentConversation.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conversations", style = MaterialTheme.typography.titleLarge, color = CielColors.Text, modifier = Modifier.weight(1f))
                Button(onClick = { conv.newConversation(); onOpened() }) { Text("New") }
            }
        }
        if (list.isEmpty()) item { Text("No conversations yet. Say \"Hey Ciel\" or type on the home screen.", color = CielColors.Muted) }
        items(list, key = { it.conversationId }) { c ->
            CielPanel(accent = if (c.conversationId == current) CielColors.Cyan else CielColors.PanelBorder) {
                Column(Modifier.fillMaxWidth().clickable { conv.open(c.conversationId); onOpened() }) {
                    Text(c.title?.take(80) ?: "Conversation", color = CielColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${c.count} messages · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.lastAt))}",
                        color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { scope.launch { conv.delete(c.conversationId) } }) { Text("Delete", color = CielColors.Danger) }
            }
        }
    }
}

@Composable
private fun HubRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CielColors.Text, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = CielColors.Muted)
    }
}

/** Settings: a short list of plain-language sections. Technical options live under Developer. */
@Composable
fun SettingsHub(nav: NavHostController) {
    val graph = CielApplication.graph
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
        CielPanel {
            HubRow("Appearance", "${s.appearance.design.title} design · accent · animation · light/dark") { nav.go("appearance") }
            HubRow("Voice & Hey Ciel", if (s.wakeWordEnabled) "Hey Ciel is on · voice, speed, activation" else "Turn on \"Hey Ciel\" · voice, speed") { nav.go("voice") }
            HubRow("AI connection", if (s.providers.isEmpty()) "Not connected" else "${s.providers.map { it.id.substringBefore(':') }.distinct().size} provider(s) connected") { nav.go("connect") }
            HubRow("Memory", "What Ciel remembers, and what she may remember") { nav.go("memory") }
            HubRow("Skills", "Research, analysis, apps, messaging, games…") { nav.go("skills") }
            HubRow("Games", "Game profiles and automation permission") { nav.go("games") }
            HubRow("Permissions", "Microphone, notifications, floating window, screen control") { nav.go("permissions") }
            HubRow("Privacy & data", "History, keys, messaging, research, automation") { nav.go("preferences") }
            HubRow("System status", "Provider, model, network, voice, tasks") { nav.go("status") }
            HubRow("Developer", if (s.developerMode) "On — advanced provider settings visible" else "Off") { nav.go("developer") }
        }
    }
}

/** Developer: toggle and the full provider editor (URLs, headers, models). */
@Composable
fun DeveloperScreen(nav: NavHostController) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CielPanel(title = "Developer") {
            Toggle("Developer mode", s.developerMode) { v -> scope.launch { graph.settings.update { it.copy(developerMode = v) } } }
            Text("Shows base URLs, headers, model ids, tiers and limits. Normal use never needs this.", color = CielColors.Muted)
            if (s.developerMode) TextButton(onClick = { nav.go("providers") }) { Text("Advanced provider editor") }
            TextButton(onClick = { scope.launch { graph.settings.update { it.copy(onboardingDone = false) } } }) { Text("Run first-time setup again") }
        }
    }
}

/** First run: choose a design → connect AI → enable Hey Ciel. Every step can be skipped. */
@Composable
fun OnboardingScreen() {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val finish = { scope.launch { graph.settings.update { it.copy(onboardingDone = true) } }; Unit }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf("1 · Design", "2 · Connect", "3 · Hey Ciel")[step], color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (step < 2) step++ else finish() }) { Text(if (step < 2) "Skip" else "Finish") }
        }
        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Box(Modifier.weight(1f)) {
            when (step) {
                0 -> DesignPickerScreen(onChosen = { step = 1 })
                1 -> Column {
                    Box(Modifier.weight(1f)) { ConnectionsScreen(onDeveloper = {}) }
                    Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Next") }
                }
                else -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("Talk to Ciel", style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
                    Spacer(Modifier.height(8.dp))
                    HeyCielCard()
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Start using Ciel") }
                }
            }
        }
    }
}

/** Voice settings entry: Hey Ciel first, then the rest of the voice preferences. */
@Composable
fun VoiceScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HeyCielCard()
        CielPanel(title = "Voice") {
            Text("Choose Ciel's voice, speaking speed and continuous conversation in Voice & preferences.", color = CielColors.Muted)
            TextButton(onClick = { nav.go("preferences") }) { Text("Voice & preferences") }
            TextButton(onClick = { nav.go("permissions") }) { Text("Microphone permission") }
        }
    }
}
