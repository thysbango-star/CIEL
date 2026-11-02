@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.ciel.app.ui.screens

import androidx.compose.foundation.layout.FlowRow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.ciel.app.CielApplication
import dev.ciel.app.data.ActionLogEntity
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.go
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.app.voice.WakeWordService
import dev.ciel.game.AutomationPolicy
import dev.ciel.game.GameProfile
import dev.ciel.memory.MemoryCategory
import dev.ciel.memory.MemoryItem
import dev.ciel.memory.MemoryManager
import dev.ciel.skills.DeclarativeSkill
import dev.ciel.task.TaskStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Status dashboard (Part 15) and entry points to management screens. */
@Composable
fun SystemHub(nav: NavHostController) {
    val graph = CielApplication.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val online by graph.network.online.collectAsStateWithLifecycle()
    val tasks by graph.tasks.tasks.collectAsStateWithLifecycle()
    val wake by WakeWordService.status.collectAsStateWithLifecycle()
    val memories by remember { graph.memory.observe() }.collectAsStateWithLifecycle(emptyList())
    val voiceReady by graph.voice.ready.collectAsStateWithLifecycle()
    val skillStatus by graph.skills.status.collectAsStateWithLifecycle()
    val preferred = settings.providers.firstOrNull { it.id == settings.engine.preferredProviderId }
    val caps = graph.capabilities

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CielPanel(title = "System status") {
                StatusLine("AI provider", preferred?.displayName ?: if (settings.providers.isEmpty()) "none configured" else "automatic")
                StatusLine("Model", preferred?.model ?: "-")
                StatusLine("Selection", if (settings.engine.autoSelect) "automatic" else "manual")
                StatusLine("Internet", if (online) "online" else "offline")
                StatusLine("Voice output", if (voiceReady) "ready" else "unavailable")
                StatusLine("Speech input", if (graph.speech.isAvailable) "available" else "no recogniser installed")
                StatusLine("Wake word", wake)
                StatusLine("Memory", if (settings.memory.enabled) "${memories.size} items" else "disabled")
                StatusLine("Active tasks", tasks.count { it.isActive }.toString())
                StatusLine("Screen control", if (caps.accessibilityEnabled()) "enabled" else "off")
                StatusLine("Skills", "${skillStatus.count { it.enabled && it.missingCapabilities.isEmpty() }} ready / ${skillStatus.size}")
            }
        }
        item {
            CielPanel(title = "Manage") {
                listOf("providers" to "AI providers & keys", "permissions" to "Permissions", "memory" to "Memory", "skills" to "Skills", "games" to "Game profiles")
                    .forEach { (r, l) -> TextButton(onClick = { nav.go(r) }) { Text(l, color = CielColors.Cyan) } }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = CielColors.Muted)
        Text(value, color = CielColors.Text)
    }
}

/** Memory controls (Part 6): view, search, edit, delete, disable, retention. */
@Composable
fun MemoryScreen() {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val all by remember { graph.memory.observe() }.collectAsStateWithLifecycle(emptyList())
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var newText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val shown = if (query.isBlank()) all else {
        val q = MemoryManager.tokens(query)
        all.filter { m -> MemoryManager.tokens(m.content).any { it in q } || m.content.contains(query, true) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            CielPanel(title = "Memory settings") {
                Toggle("Memory enabled", settings.memory.enabled) { v -> scope.launch { graph.settings.update { it.copy(memory = it.memory.copy(enabled = v)) } } }
                Toggle("Ciel may save things she infers", settings.memory.allowInferredMemories) { v -> scope.launch { graph.settings.update { it.copy(memory = it.memory.copy(allowInferredMemories = v)) } } }
                Toggle("Share relevant memories with AI providers", settings.engine.shareMemoryWithProviders) { v -> scope.launch { graph.settings.update { it.copy(engine = it.engine.copy(shareMemoryWithProviders = v)) } } }
                Text("Retain:", color = CielColors.Muted)
                MemoryCategory.values().forEach { c ->
                    Toggle(c.label, c in settings.memory.retainedCategories) { v -> scope.launch { graph.settings.update {
                        val set = if (v) it.memory.retainedCategories + c else it.memory.retainedCategories - c
                        it.copy(memory = it.memory.copy(retainedCategories = set)) } } }
                }
                Text("Passwords, card numbers, one-time codes, ID numbers and API keys are never stored.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newText, { newText = it }, Modifier.weight(1f), label = { Text("Add a memory") })
                TextButton(onClick = { scope.launch {
                    notice = when (val r = graph.memory.remember(newText, MemoryCategory.PREFERENCE)) {
                        is MemoryManager.SaveResult.Saved -> { newText = ""; "Saved." }
                        is MemoryManager.SaveResult.Rejected -> r.reason
                    }
                } }) { Text("Save") }
            }
            notice?.let { Text(it, color = CielColors.Amber) }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search memories") }, singleLine = true)
        }
        items(shown, key = { it.id }) { m ->
            CielPanel(title = m.category.label) {
                Text(m.content, color = CielColors.Text)
                Text("${m.source} · ${DateFormat.getDateInstance().format(Date(m.updatedAt))}", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { editing = m }) { Text("Edit") }
                    TextButton(onClick = { scope.launch { graph.memory.forget(m.id) } }) { Text("Delete", color = CielColors.Danger) }
                }
            }
        }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            var backupNote by remember { mutableStateOf<String?>(null) }
            val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) scope.launch {
                    backupNote = runCatching {
                        val text = dev.ciel.memory.MemoryBackup.export(graph.memory.search("", limit = 10_000))
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Couldn't open the file")
                        "Exported ${all.size} memories."
                    }.getOrElse { "Export failed: ${it.message}" }
                }
            }
            val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    backupNote = runCatching {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Couldn't open the file")
                        val r = dev.ciel.memory.MemoryBackup.import(text, graph.memory)
                        "Imported ${r.imported}, skipped ${r.skipped}." + (r.reasons.firstOrNull()?.let { " ($it)" } ?: "")
                    }.getOrElse { "Import failed: ${it.message}" }
                }
            }
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("ciel-memories.json") }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import backup") }
            }
            backupNote?.let { Text(it, color = CielColors.Muted) }
        }
        item {
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirm = true }) { Text("Delete all memories", color = CielColors.Danger) }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete every memory?") }, text = { Text("This cannot be undone.") },
                confirmButton = { TextButton(onClick = { scope.launch { graph.memory.forgetEverything(); confirm = false } }) { Text("Delete all", color = CielColors.Danger) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.content) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit memory") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), maxLines = 10) },
            confirmButton = { TextButton(onClick = { scope.launch {
                when (val r = graph.memory.edit(m.id, text)) { is MemoryManager.SaveResult.Rejected -> notice = r.reason; else -> Unit }
                editing = null } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}

/** Skills (Part 12): enable/disable, missing permissions, install JSON skills without rebuilding. */
@Composable
fun SkillsScreen() {
    val graph = CielApplication.graph
    val status by graph.skills.status.collectAsStateWithLifecycle()
    var install by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.skills.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { install = true }) { Text("Install a skill (JSON)") } }
        items(status, key = { it.skill.id }) { s ->
            CielPanel(title = s.skill.displayName) {
                Text(s.skill.description, color = CielColors.Text)
                Text("Tools: " + s.skill.tools.joinToString { it.name } + if (s.skill.worksOffline) " · works offline" else "", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                if (s.missingCapabilities.isNotEmpty()) Text("Needs: " + s.missingCapabilities.joinToString { it.name.lowercase().replace('_', ' ') }, color = CielColors.Amber)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.enabled) "Enabled" else "Disabled", Modifier.weight(1f), color = CielColors.Muted)
                    Switch(s.enabled, { graph.skills.setEnabled(s.skill.id, it) })
                }
                if (s.skill is DeclarativeSkill) TextButton(onClick = { graph.uninstallSkill(s.skill.id) }) { Text("Uninstall", color = CielColors.Danger) }
            }
        }
    }
    if (install) {
        var json by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { install = false }, title = { Text("Install skill") },
            text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Paste a skill definition. Skills can only use Ciel's guarded actions (see ARCHITECTURE.md → Skills).", color = CielColors.Muted)
                OutlinedTextField(json, { json = it }, Modifier.fillMaxWidth(), minLines = 6)
                error?.let { Text(it, color = CielColors.Danger) }
            } },
            confirmButton = { TextButton(onClick = {
                try { graph.installSkill(json); install = false } catch (e: IllegalArgumentException) { error = e.message }
            }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { install = false }) { Text("Cancel") } })
    }
}
