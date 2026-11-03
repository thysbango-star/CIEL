@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ciel.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.ciel.desktop.Chip
import dev.ciel.desktop.Dest
import dev.ciel.desktop.core.ChatLine
import dev.ciel.desktop.core.DesktopGraph
import dev.ciel.desktop.core.DesktopPaths
import dev.ciel.memory.MemoryBackup
import dev.ciel.windows.Autonomy
import dev.ciel.windows.human
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SwitchRow(title: String, subtitle: String? = null, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = C.text)
            subtitle?.let { Text(it, color = C.muted, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(value, onChange)
    }
}

@Composable
fun HomeScreen(graph: DesktopGraph, go: (Dest) -> Unit) {
    val state by graph.conversation.state.collectAsState()
    val lines by graph.conversation.lines.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Crystal(state, Modifier.size(260.dp).padding(top = 12.dp))
        Text(stateLabel(state), color = C.muted)
        Spacer(Modifier.height(8.dp))
        Text(lines.lastOrNull { it.role == "ciel" }?.text?.take(240) ?: "“I am here, ${graph.settings.current.formOfAddress}.”",
            color = C.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask Ciel…") }, singleLine = true)
            Button(onClick = { graph.conversation.send(input); input = ""; go(Dest.CHAT) }, enabled = input.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 900.dp)) {
            QuickCard("What's taking up space?", "Scan your folders and see where storage goes.") { go(Dest.STORAGE) }
            QuickCard("Gaming Mode", if (graph.pc.gaming.isActive) "On — tap to restore everything." else "Prepare the laptop for a game.") { go(Dest.GAMING) }
            QuickCard("PC health", "Storage, memory, battery, updates, drivers.") { go(Dest.PC) }
            QuickCard("Download something", "Ciel checks it and asks before saving.") { go(Dest.DOWNLOADS) }
        }
        if (graph.settings.current.providers.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.widthIn(max = 640.dp), accent = C.amber) {
                Text("No AI connected yet. PC tools work without one; to chat, connect Ollama on this PC or another provider.", color = C.text)
                TextButton(onClick = { go(Dest.CONNECT) }) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun QuickCard(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 200.dp, max = 210.dp).background(C.panel, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp)) {
        Text(title, color = C.accent, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, color = C.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ChatScreen(graph: DesktopGraph) {
    val lines by graph.conversation.lines.collectAsState()
    val streaming by graph.conversation.streaming.collectAsState()
    val tools by graph.conversation.tools.collectAsState()
    val state by graph.conversation.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, streaming.length / 60) { val n = lines.size + (if (streaming.isNotEmpty()) 1 else 0); if (n > 0) list.animateScrollToItem(n - 1) }
    fun send() { if (input.isNotBlank()) { graph.conversation.send(input); input = "" } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lines.isEmpty()) item { Text("Ask anything — \"what's taking up my storage?\", \"turn on Gaming Mode for Minecraft\", \"find old installers\".", color = C.muted) }
            items(lines) { Bubble(it) }
            if (streaming.isNotEmpty()) item { Bubble(ChatLine("ciel", streaming)) }
        }
        if (tools.isNotEmpty()) Text(tools.joinToString("  ") { (if (!it.done) "◌ " else if (it.ok == true) "✓ " else "✗ ") + it.tool }, color = C.muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f).heightIn(min = 56.dp, max = 180.dp).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) { send(); true } else false
            }, placeholder = { Text("Message Ciel (Enter to send, Shift+Enter for a new line)") }, maxLines = 8)
            if (state != dev.ciel.engine.CielState.IDLE) OutlinedButton(onClick = { graph.conversation.stop() }, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
            else Button(onClick = ::send, enabled = input.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        }
    }
}

@Composable
private fun Bubble(l: ChatLine) {
    val mine = l.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 760.dp).background(if (mine) C.accent.copy(alpha = 0.15f) else C.panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
            if (!mine) Text("CIEL", color = C.accent, style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(l.text, color = C.text) }
        }
    }
}

@Composable
fun TasksScreen(graph: DesktopGraph) {
    val tasks by graph.tasks.tasks.collectAsState()
    var log by remember { mutableStateOf(graph.pc.activity.recent()) }
    LaunchedEffect(Unit) { while (true) { delay(3000); log = graph.pc.activity.recent() } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Tasks", color = C.text, style = MaterialTheme.typography.headlineSmall) }
        if (tasks.isEmpty()) item { Text("No tasks yet. Ask Ciel in Chat to do something; running and finished tasks appear here.", color = C.muted) }
        items(tasks.reversed()) { t ->
            Panel(title = t.name) {
                Text("${t.status.name.lowercase()} · ${t.currentStep ?: ""}", color = C.muted)
                t.report?.let { Text(it, color = C.text) }
                if (t.isActive) Row { TextButton(onClick = { graph.tasks.pause(t.id) }) { Text("Pause") }; TextButton(onClick = { graph.tasks.cancel(t.id) }) { Text("Cancel", color = C.danger) } }
            }
        }
        item { Text("Activity log — what Ciel did on this PC", color = C.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        if (log.isEmpty()) item { Text("Nothing yet.", color = C.muted) }
        items(log) { e ->
            Text("${java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)}  ${if (e.ok) "✓" else "✗"} ${e.what} — ${e.why} (${e.permission})",
                color = if (e.ok) C.text else C.amber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MemoryScreen(graph: DesktopGraph) {
    val scope = rememberCoroutineScope()
    val items by graph.memory.observe().collectAsState(emptyList())
    var note by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Memory", color = C.text, style = MaterialTheme.typography.headlineSmall)
            Text("What Ciel remembers about you. Delete anything; export a backup to move it between devices.", color = C.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { scope.launch {
                    val f = File(System.getProperty("user.home"), "Documents/ciel-memories.json"); f.parentFile.mkdirs()
                    f.writeText(MemoryBackup.export(graph.memory.observe().first())); note = "Exported to ${f.absolutePath}"
                } }) { Text("Export") }
                OutlinedButton(onClick = { scope.launch {
                    val f = File(System.getProperty("user.home"), "Documents/ciel-memories.json")
                    note = if (!f.exists()) "Put ciel-memories.json (exported from Ciel on your phone) in Documents first." else MemoryBackup.import(f.readText(), graph.memory).let { "Imported ${it.imported}, skipped ${it.skipped}." }
                } }) { Text("Import from Documents") }
                TextButton(onClick = { scope.launch { graph.memory.forgetEverything() } }) { Text("Delete all", color = C.danger) }
            }
            note?.let { Text(it, color = C.accent) }
        }
        items(items) { m ->
            Row(Modifier.fillMaxWidth().background(C.panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.category.name.lowercase(), color = C.accent, style = MaterialTheme.typography.labelSmall)
                    Text(m.content, color = C.text)
                }
                TextButton(onClick = { scope.launch { graph.memory.forget(m.id) } }) { Text("Forget", color = C.danger) }
            }
        }
    }
}

@Composable
fun SettingsScreen(graph: DesktopGraph) {
    val s by graph.settings.flow.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", color = C.text, style = MaterialTheme.typography.headlineSmall)
        Panel(title = "Theme") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CielTheme.values().forEach { t -> Chip(t.title, s.theme == t.name) { graph.settings.update { it.copy(theme = t.name) } } } }
        }
        Panel(title = "How much Ciel may do on her own") {
            Autonomy.values().forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { graph.settings.update { it.copy(autonomy = a) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(s.autonomy == a, { graph.settings.update { it.copy(autonomy = a) } })
                    Column { Text(a.label, color = C.text); Text(a.description, color = C.muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("Always asked, whatever this says: removing personal files, installing or running programs, security settings, and anything that can lose data.", color = C.muted, style = MaterialTheme.typography.bodySmall)
        }
        Panel(title = "Ciel") {
            var addr by remember(s.formOfAddress) { mutableStateOf(s.formOfAddress) }
            OutlinedTextField(addr, { addr = it; graph.settings.update { st -> st.copy(formOfAddress = it) } }, label = { Text("How Ciel addresses you") }, singleLine = true)
            SwitchRow("Floating assistant", "A small Ciel window on your desktop.", s.floatingAssistant) { v -> graph.settings.update { it.copy(floatingAssistant = v) } }
            SwitchRow("Performance overlay", "CPU, memory and GPU in a small always-on-top window.", s.perfOverlay) { v -> graph.settings.update { it.copy(perfOverlay = v) } }
            SwitchRow("Speak replies", "Uses Windows' own voices, on this PC.", s.speakReplies) { v -> graph.settings.update { it.copy(speakReplies = v) } }
        }
        Panel(title = "About") {
            Text("Data folder: ${DesktopPaths.dataDir.absolutePath}", color = C.muted)
            Text("API keys are encrypted with Windows (DPAPI) for your user account and never leave this PC.", color = C.muted)
            Text("Not yet on Windows: voice input / \"Hey Ciel\", sync with Ciel on your phone (use Memory → Export/Import for now).", color = C.muted)
        }
    }
}

/** A small always-on-top Ciel on the desktop: status, quick question, open, stop. */
@Composable
fun FloatingAssistant(graph: DesktopGraph, theme: CielTheme, onOpen: () -> Unit) {
    val state by graph.conversation.state.collectAsState()
    val lines by graph.conversation.lines.collectAsState()
    var input by remember { mutableStateOf("") }
    val ws = rememberWindowState(size = DpSize(340.dp, 200.dp), position = WindowPosition(Alignment.BottomEnd))
    Window(onCloseRequest = { graph.settings.update { it.copy(floatingAssistant = false) } }, state = ws, title = "Ciel", undecorated = true, transparent = true,
        alwaysOnTop = true, resizable = false) {
        CielDesktopTheme(theme) {
            DragArea {
                Column(Modifier.fillMaxSize().padding(6.dp).background(C.panel.copy(alpha = 0.92f), RoundedCornerShape(20.dp)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Crystal(state, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("Ciel · ${stateLabel(state)}", color = C.accent, style = MaterialTheme.typography.labelMedium)
                            Text(lines.lastOrNull { it.role == "ciel" }?.text?.take(90) ?: "Ready.", color = C.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(input, { input = it }, Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { graph.conversation.send(input); input = ""; true } else false
                        }, placeholder = { Text("Ask Ciel…") }, singleLine = true)
                    }
                    Row {
                        TextButton(onClick = onOpen) { Text("Open") }
                        TextButton(onClick = { graph.emergencyStop.trigger("floating stop") }) { Text("Stop", color = C.danger) }
                        TextButton(onClick = { graph.settings.update { it.copy(floatingAssistant = false) } }) { Text("Hide", color = C.muted) }
                    }
                }
            }
        }
    }
}

/** Live performance overlay; samples every 2 s, only what Windows can measure. */
@Composable
fun PerfOverlay(graph: DesktopGraph, theme: CielTheme) {
    val sample by remember { graph.pc.monitor.samples(2) }.collectAsState(graph.pc.monitor.basic())
    val ws = rememberWindowState(size = DpSize(230.dp, 150.dp), position = WindowPosition(Alignment.TopEnd))
    Window(onCloseRequest = { graph.settings.update { it.copy(perfOverlay = false) } }, state = ws, title = "Ciel monitor", undecorated = true, transparent = true,
        alwaysOnTop = true, resizable = false, focusable = false) {
        CielDesktopTheme(theme) {
            DragArea {
                Column(Modifier.fillMaxSize().padding(4.dp).background(Color(0xCC061633), RoundedCornerShape(14.dp)).padding(10.dp)) {
                    Text(if (graph.pc.gaming.isActive) "Gaming Mode ON" else "Ciel monitor", color = C.accent, style = MaterialTheme.typography.labelMedium)
                    PerfLines(sample)
                }
            }
        }
    }
}

@Composable
fun PerfLines(s: dev.ciel.windows.PerfSample) {
    @Composable fun l(k: String, v: String?) = Text("$k  ${v ?: "n/a"}", color = if (v == null) C.muted else C.text, style = MaterialTheme.typography.bodySmall)
    l("CPU", s.cpuPercent?.let { "%.0f%%".format(it) })
    l("RAM", if (s.ramUsedBytes != null && s.ramTotalBytes != null) "${human(s.ramUsedBytes!!)} / ${human(s.ramTotalBytes!!)}" else null)
    l("GPU", s.gpuPercent?.let { "%.0f%%".format(it) })
    l("GPU mem", s.gpuMemoryBytes?.let(::human))
    l("GPU temp", s.gpuTempC?.let { "%.0f °C".format(it) })
    l("Disk", s.diskPercent?.let { "%.0f%%".format(it.coerceAtMost(100.0)) })
}

@Composable
private fun androidx.compose.ui.window.FrameWindowScope.DragArea(content: @Composable () -> Unit) {
    WindowDraggableArea { Box { content() } }
}
