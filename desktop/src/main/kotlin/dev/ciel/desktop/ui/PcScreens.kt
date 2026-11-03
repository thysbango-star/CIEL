@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ciel.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ciel.ai.ConnectResult
import dev.ciel.ai.InferenceLocation
import dev.ciel.ai.ProviderPresets
import dev.ciel.desktop.Chip
import dev.ciel.desktop.core.DesktopGraph
import dev.ciel.research.SearchProviderKind
import dev.ciel.task.ConfirmationRequest
import dev.ciel.task.Risk
import dev.ciel.windows.CleanupCandidate
import dev.ciel.windows.CleanupPlanner
import dev.ciel.windows.CleanupRisk
import dev.ciel.windows.DownloadProposal
import dev.ciel.windows.DownloadResult
import dev.ciel.windows.Drives
import dev.ciel.windows.GameDetector
import dev.ciel.windows.GamingPlan
import dev.ciel.windows.GamingPlanner
import dev.ciel.windows.GamingPreset
import dev.ciel.windows.HealthItem
import dev.ciel.windows.HealthStatus
import dev.ciel.windows.ProcessClassifier
import dev.ciel.windows.ProcessInfo
import dev.ciel.windows.ScanResult
import dev.ciel.windows.WindowsSystemImpl
import dev.ciel.windows.human
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Paths

private fun date(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

@Composable
private fun H(text: String) = Text(text, color = C.text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Muted(text: String) = Text(text, color = C.muted, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Bar(fraction: Float, warn: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(C.border.copy(alpha = 0.25f), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(if (warn) C.amber else C.accent, RoundedCornerShape(4.dp)))
    }
}

private fun open(graph: DesktopGraph, path: String) = graph.scope.launch { (graph.windows as WindowsSystemImpl).openFolder(path) }

// ───────────────────────────── Storage ─────────────────────────────

/** Survives switching screens: a scan keeps running in the background. */
private object StorageUi {
    var scanning by mutableStateOf(false)
    var progress by mutableStateOf("")
    var job: Job? = null
    var result by mutableStateOf<ScanResult?>(null)
    var plan by mutableStateOf<CleanupPlanner.Plan?>(null)
    var planning by mutableStateOf(false)
    var selected by mutableStateOf(setOf<String>())
    var message by mutableStateOf<String?>(null)
    var tab by mutableStateOf(0)
}

@Composable
fun StorageScreen(graph: DesktopGraph) {
    val ui = StorageUi
    val pc = graph.pc
    val drives = remember { Drives.list() }
    fun startScan(roots: List<java.nio.file.Path>) {
        ui.scanning = true; ui.message = null; ui.plan = null; ui.selected = emptySet()
        ui.job = graph.scope.launch {
            val r = runCatching { pc.scan(roots, { files, bytes, cur -> ui.progress = "$files files · ${human(bytes)} · ${cur.takeLast(70)}" }) }
            ui.result = r.getOrNull() ?: ui.result
            r.exceptionOrNull()?.let { if (it !is kotlinx.coroutines.CancellationException) ui.message = "Scan stopped: ${it.message}" }
            ui.scanning = false
        }
    }
    fun makePlan(dups: Boolean) {
        ui.planning = true
        graph.scope.launch { ui.plan = runCatching { pc.plan(dups) }.getOrNull(); ui.planning = false; ui.tab = 3 }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            H("Storage")
            Muted("Everything is measured on this PC. No file names or contents are sent to an AI provider.")
        }
        item {
            Panel(title = "Drives") {
                drives.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.root} ${d.label.orEmpty()}", color = C.text, modifier = Modifier.width(160.dp))
                        Column(Modifier.weight(1f)) {
                            Bar(d.usedPercent / 100f, d.usedPercent >= 90)
                            Muted("${human(d.freeBytes)} free of ${human(d.totalBytes)} · ${d.usedPercent}% used${d.fileSystem?.let { " · $it" } ?: ""}")
                        }
                        TextButton(enabled = !ui.scanning, onClick = { startScan(listOf(Paths.get(d.root))) }) { Text("Scan drive") }
                    }
                }
            }
        }
        item {
            Panel(title = "Find what's taking up space") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !ui.scanning, onClick = { startScan(pc.quickRoots()) }) { Text("Quick scan (your folders, apps, temp)") }
                    if (ui.scanning) OutlinedButton(onClick = { ui.job?.cancel(); ui.scanning = false }) { Text("Cancel") }
                }
                if (ui.scanning) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)); Muted(ui.progress) }
                ui.message?.let { Text(it, color = C.amber) }
                val hist = remember(ui.result) { pc.history.all().takeLast(5).reversed() }
                if (hist.isNotEmpty()) {
                    Muted("Previous scans:")
                    hist.forEach { h -> Muted("${date(h.at)} — ${human(h.totalBytes)} · ${h.roots.take(70)}") }
                }
                Exclusions(graph)
            }
        }
        val r = ui.result
        if (r != null) {
            item {
                Muted(r.summary().lineSequence().first() + " in ${r.durationMs / 1000}s" + if (r.skipped > 0) " · ${r.skipped} unreadable item(s) skipped" else "")
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Categories", "Largest files", "Largest folders", "Clean-up suggestions").forEachIndexed { i, t -> Chip(t, ui.tab == i) { ui.tab = i } }
                }
            }
            when (ui.tab) {
                0 -> {
                    val max = r.categories.maxOfOrNull { it.bytes }?.coerceAtLeast(1) ?: 1
                    items(r.categories.filter { it.bytes > 0 }) { c ->
                        Column(Modifier.fillMaxWidth().background(C.panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
                            Row { Text(c.category.label, color = C.text, modifier = Modifier.weight(1f)); Text(human(c.bytes), color = C.accent) }
                            Bar(c.bytes.toFloat() / max)
                            Muted("${c.files} files" + (c.top.firstOrNull()?.let { " · largest: ${it.path.substringAfterLast('\\').substringAfterLast('/')} (${human(it.size)})" } ?: ""))
                        }
                    }
                }
                1 -> items(r.largestFiles.take(150)) { f ->
                    PathRow(f.path, human(f.size), "${f.category.label} · modified ${date(f.modified)}") { open(graph, f.path) }
                }
                2 -> items(r.largestFolders.take(120)) { f -> PathRow(f.path, human(f.size), "${f.files} files") { open(graph, f.path) } }
                else -> {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !ui.planning, onClick = { makePlan(false) }) { Text("Suggest clean-up") }
                            OutlinedButton(enabled = !ui.planning, onClick = { makePlan(true) }) { Text("…and find duplicates") }
                        }
                        if (ui.planning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    ui.plan?.let { p -> cleanupItems(graph, p) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.cleanupItems(graph: DesktopGraph, p: CleanupPlanner.Plan) {
    val ui = StorageUi
    item {
        Panel(accent = C.accent) {
            Text("Nothing is removed until you tick items and approve.", color = C.text)
            Muted("Low risk: temporary & cache data (${human(p.lowBytes)}). Review: your files that look unneeded (${human(p.reviewBytes)}) — they go to the Recycle Bin, so you can restore them.")
            val chosen = (p.low + p.review).filter { it.path in ui.selected }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = { ui.selected = ui.selected + p.low.filter { !it.inUse }.map { it.path } }) { Text("Select all low risk") }
                TextButton(onClick = { ui.selected = emptySet() }) { Text("Clear") }
                Button(enabled = chosen.isNotEmpty(), onClick = { graph.scope.launch { runCleanup(graph, chosen) } }) { Text("Clean up ${chosen.size} (${human(chosen.sumOf { it.size })})") }
            }
        }
    }
    if (p.low.isNotEmpty()) item { Text(CleanupRisk.LOW.label, color = C.green, style = MaterialTheme.typography.titleMedium) }
    items(p.low) { CandidateRow(graph, it) }
    if (p.review.isNotEmpty()) item { Text(CleanupRisk.REVIEW.label, color = C.amber, style = MaterialTheme.typography.titleMedium) }
    items(p.review) { CandidateRow(graph, it) }
    if (p.protected.isNotEmpty()) item {
        Text(CleanupRisk.PROTECTED.label, color = C.muted, style = MaterialTheme.typography.titleMedium)
        Muted("Large but important — system files, programs, saved games, browser profiles, password databases. Shown for information only.")
    }
    items(p.protected.take(30)) { c -> PathRow(c.path, human(c.size), c.reason) { open(graph, c.path) } }
    item {
        val hist = graph.pc.executor.history().takeLast(15).reversed()
        if (hist.isNotEmpty()) Panel(title = "Clean-up history") {
            hist.forEach { h -> Muted("${date(h.at)} ${if (h.ok) "✓" else "✗"} ${h.path.takeLast(80)} · ${human(h.bytes)} · ${h.method.label.substringBefore(" (")}${if (h.note.isNotBlank()) " — ${h.note}" else ""}") }
            Muted("Items moved to the Recycle Bin can be restored from the Recycle Bin on your desktop.")
        }
    }
}

private suspend fun runCleanup(graph: DesktopGraph, chosen: List<CleanupCandidate>) {
    val ui = StorageUi
    val detail = chosen.joinToString("\n") { "• ${it.path} (${human(it.size)}) — ${it.method.label}" } +
        "\n\nCiel checks each item again just before removing it and skips anything that changed or is in use."
    if (!graph.confirmations.confirm(ConfirmationRequest("Clean up ${chosen.size} item(s), ${human(chosen.sumOf { it.size })}?", detail, Risk.CONSEQUENTIAL, "Clean up storage"))) return
    val records = graph.pc.executor.execute(chosen)
    val freed = records.filter { it.ok }.sumOf { it.bytes }
    graph.pc.activity.add("Cleaned ${records.count { it.ok }} item(s), ${human(freed)}", "You approved the clean-up in Storage", "approved", records.all { it.ok })
    ui.message = "Freed ${human(freed)}. ${records.count { !it.ok }.takeIf { it > 0 }?.let { "$it item(s) skipped (in use or changed)." } ?: ""}"
    ui.selected = emptySet()
    ui.plan = ui.plan?.let { p -> val gone = records.filter { it.ok }.map { it.path }.toSet(); p.copy(low = p.low.filter { it.path !in gone }, review = p.review.filter { it.path !in gone }) }
}

@Composable
private fun CandidateRow(graph: DesktopGraph, c: CleanupCandidate) {
    val ui = StorageUi
    val on = c.path in ui.selected
    Row(Modifier.fillMaxWidth().background(C.panel, RoundedCornerShape(12.dp)).clickable(enabled = !c.inUse) { ui.selected = if (on) ui.selected - c.path else ui.selected + c.path }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, { ui.selected = if (it) ui.selected + c.path else ui.selected - c.path }, enabled = !c.inUse)
        Column(Modifier.weight(1f)) {
            Text(c.path, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${c.reason} · ${c.method.label}${if (c.inUse) " · in use, skipped" else ""}")
        }
        Text(human(c.size), color = C.accent, modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = { open(graph, c.path) }) { Text("Show") }
    }
}

@Composable
private fun PathRow(path: String, size: String, sub: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(C.panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(path, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis); Muted(sub) }
        Text(size, color = C.accent, modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = onOpen) { Text("Open location") }
    }
}

@Composable
private fun Exclusions(graph: DesktopGraph) {
    val s by graph.settings.flow.collectAsState()
    var add by remember { mutableStateOf("") }
    Muted("Never scan these folders:")
    s.scanExclusions.forEach { e -> Row(verticalAlignment = Alignment.CenterVertically) { Text(e, color = C.text, modifier = Modifier.weight(1f)); TextButton(onClick = { graph.settings.update { it.copy(scanExclusions = it.scanExclusions - e) } }) { Text("Remove") } } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(add, { add = it }, Modifier.weight(1f), placeholder = { Text("e.g. D:\\Private") }, singleLine = true)
        TextButton(enabled = add.isNotBlank(), onClick = { graph.settings.update { it.copy(scanExclusions = (it.scanExclusions + add.trim()).distinct()) }; add = "" }) { Text("Exclude") }
    }
}

// ───────────────────────────── Gaming ─────────────────────────────

private object GamingUi {
    var preset by mutableStateOf(GamingPreset.BALANCED)
    var profile by mutableStateOf<String?>(null)
    var plan by mutableStateOf<GamingPlan?>(null)
    var approved by mutableStateOf(setOf<String>())
    var busy by mutableStateOf(false)
    var report by mutableStateOf<List<String>>(emptyList())
    var detected by mutableStateOf<String?>(null)
}

@Composable
fun GamingScreen(graph: DesktopGraph) {
    val ui = GamingUi
    val pc = graph.pc
    val s by graph.settings.flow.collectAsState()
    var active by remember { mutableStateOf(pc.gaming.isActive) }
    val sample by remember { pc.monitor.samples(2) }.collectAsState(pc.monitor.basic())

    fun buildPlan() {
        ui.busy = true
        graph.scope.launch {
            runCatching {
                val procs = pc.system.processes()
                val det = GameDetector.detect(procs, pc.system.foregroundPid(), s.gameProfiles)
                val prof = s.gameProfiles.firstOrNull { it.name == ui.profile } ?: det?.second
                ui.detected = det?.let { "${it.second?.name ?: it.first.name} is running" }
                val p = GamingPlanner.plan(procs, ui.preset, prof, pc.system.powerSchemes(), det?.first?.pid, pc.processPids(), pc.system.gameModeEnabled(), pc.system.gpuSchedulingEnabled())
                ui.plan = p; ui.approved = p.close.map { it.process.exe }.toSet()
            }.onFailure { ui.report = listOf("Couldn't read running programs: ${it.message}") }
            ui.busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            H("Gaming Mode")
            Muted("Ciel records how everything was, closes only what you approve (politely, never forcing apps with unsaved work), and puts it all back when you're done. Defender, firewall, drivers, audio and network are never touched.")
        }
        item {
            Panel(title = "Right now") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { Column { PerfLines(sample) }; Column { Muted(ui.detected ?: "No game detected yet.") } }
            }
        }
        if (active) item {
            Panel(title = "Gaming Mode is ON", accent = C.green) {
                Muted("Original settings are saved. Turning it off restores your power plan and reopens the apps Ciel closed.")
                Button(enabled = !ui.busy, onClick = {
                    ui.busy = true
                    graph.scope.launch {
                        val r = pc.gaming.deactivate(); ui.report = r.lines; active = pc.gaming.isActive; ui.plan = null; ui.busy = false
                        pc.activity.add("Gaming Mode off", "You turned it off", "your request", r.ok)
                    }
                }) { Text("Turn off & restore everything") }
            }
        } else {
            item {
                Panel(title = "Prepare") {
                    Muted("Preset")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { GamingPreset.values().forEach { p -> Chip(p.label, ui.preset == p) { ui.preset = p; ui.plan = null } } }
                    Muted(ui.preset.description)
                    Muted("Game profile")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Auto-detect", ui.profile == null) { ui.profile = null; ui.plan = null }
                        s.gameProfiles.forEach { g -> Chip(g.name, ui.profile == g.name) { ui.profile = g.name; ui.plan = null } }
                    }
                    Row(Modifier.padding(top = 8.dp)) { Button(enabled = !ui.busy, onClick = ::buildPlan) { Text(if (ui.plan == null) "Show what Ciel would change" else "Refresh plan") } }
                    if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            ui.plan?.let { p ->
                item {
                    Panel(title = "Plan — untick anything you want left open", accent = C.accent) {
                        p.powerScheme?.let { Text("Power plan → ${it.name}", color = C.text) } ?: Muted("Power plan stays as it is.")
                        p.recommendations.forEach { Muted("• $it") }
                        if (p.close.isEmpty()) Muted("Nothing needs closing.")
                        p.close.forEach { c ->
                            val on = c.process.exe in ui.approved
                            Row(Modifier.fillMaxWidth().clickable { ui.approved = if (on) ui.approved - c.process.exe else ui.approved + c.process.exe }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, { ui.approved = if (it) ui.approved + c.process.exe else ui.approved - c.process.exe })
                                Column(Modifier.weight(1f)) {
                                    Text("${c.process.windowTitle?.takeIf { it.isNotBlank() } ?: c.process.name} · ${human(c.process.memoryBytes)}", color = C.text)
                                    Muted("${c.cls.label} — ${c.reason}${if (c.reopen) " · reopened afterwards" else ""}")
                                }
                            }
                        }
                        val chosen = p.close.filter { it.process.exe in ui.approved }
                        Muted("Frees about ${human(chosen.sumOf { it.process.memoryBytes })} of memory. Staying open: ${p.keep.size} programs (Windows, security, drivers, audio, network, your game).")
                        Button(enabled = !ui.busy, modifier = Modifier.padding(top = 8.dp), onClick = {
                            ui.busy = true
                            graph.scope.launch {
                                val r = pc.gaming.activate(p, chosen); ui.report = r.lines; active = pc.gaming.isActive; ui.busy = false
                                pc.activity.add("Gaming Mode on (${p.preset.label}), closed ${chosen.size}", "You approved the plan", "approved", r.ok)
                            }
                        }) { Text("Turn on Gaming Mode") }
                    }
                }
            }
        }
        if (ui.report.isNotEmpty()) item { Panel(title = "Report") { ui.report.forEach { Text(it, color = C.text) } } }
    }
}

// ───────────────────────────── PC health & processes ─────────────────────────────

@Composable
fun PcScreen(graph: DesktopGraph) {
    val pc = graph.pc
    var health by remember { mutableStateOf<List<HealthItem>?>(null) }
    var procs by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var startup by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val sample by remember { pc.monitor.samples(2) }.collectAsState(pc.monitor.basic())
    LaunchedEffect(Unit) {
        launch { health = runCatching { pc.health.check() }.getOrDefault(emptyList()) }
        launch { startup = runCatching { pc.health.startupAppList() }.getOrDefault(emptyList()) }
        while (true) { procs = runCatching { pc.system.processes() }.getOrDefault(procs); delay(5000) }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { H("PC management") }
        item { Panel(title = "Live") { PerfLines(sample) } }
        item {
            Panel(title = "Health check") {
                val h = health
                if (h == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Muted("Checking storage, memory, battery, updates, drivers and recent app errors…") }
                else h.forEach { i ->
                    val col = when (i.status) { HealthStatus.OK -> C.green; HealthStatus.ATTENTION -> C.amber; else -> C.muted }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(if (i.status == HealthStatus.OK) "✓" else if (i.status == HealthStatus.ATTENTION) "!" else "·", color = col, modifier = Modifier.width(20.dp))
                        Column { Text("${i.area}: ${i.detail}", color = C.text); i.recommendation?.let { Muted(it) } }
                    }
                }
                TextButton(onClick = { health = null; scope.launch { health = runCatching { pc.health.check() }.getOrDefault(emptyList()) } }) { Text("Check again") }
                Muted("Ciel never installs drivers or BIOS updates and never changes firmware, overclocking or the registry — she tells you what to look at.")
            }
        }
        item {
            Panel(title = "Startup apps") {
                if (startup.isEmpty()) Muted("None listed, or Windows didn't report them.")
                startup.take(30).forEach { (n, cmd) -> Text(n, color = C.text); Muted(cmd.take(120)) }
                TextButton(onClick = { scope.launch { pc.system.openSettings("ms-settings:startupapps") } }) { Text("Manage in Windows Settings") }
            }
        }
        item { Text("Running programs (by memory)", color = C.text, style = MaterialTheme.typography.titleMedium) }
        val groups = procs.groupBy { it.exe }.map { (_, g) -> g.maxByOrNull { it.memoryBytes }!!.copy(memoryBytes = g.sumOf { it.memoryBytes }) to g.size }.sortedByDescending { it.first.memoryBytes }.take(40)
        items(groups) { (p, n) ->
            Row(Modifier.fillMaxWidth().background(C.panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.windowTitle?.takeIf { it.isNotBlank() }?.let { "$it (${p.name})" } ?: p.name, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("${ProcessClassifier.classify(p, pc.processPids()).label}${if (n > 1) " · $n processes" else ""}")
                }
                Text(human(p.memoryBytes), color = C.accent)
            }
        }
    }
}

// ───────────────────────────── Downloads ─────────────────────────────

private object DownloadsUi {
    var url by mutableStateOf("")
    var sha by mutableStateOf("")
    var proposal by mutableStateOf<DownloadProposal?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<String?>(null)
    var result by mutableStateOf<DownloadResult?>(null)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun DownloadsScreen(graph: DesktopGraph) {
    val ui = DownloadsUi
    val dm = graph.pc.downloads
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        H("Downloads")
        Muted("Paste a link from the official site. Ciel checks it first and downloads only after you approve. Downloaded files are never opened, run or installed.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ui.url, { ui.url = it; ui.proposal = null; ui.result = null }, Modifier.weight(1f), placeholder = { Text("https://…") }, singleLine = true)
            Button(enabled = ui.url.isNotBlank() && !ui.busy, modifier = Modifier.padding(start = 8.dp), onClick = {
                ui.busy = true; ui.error = null; ui.result = null
                graph.scope.launch { runCatching { ui.proposal = dm.inspect(ui.url) }.onFailure { ui.error = it.message }; ui.busy = false }
            }) { Text("Check link") }
        }
        OutlinedTextField(ui.sha, { ui.sha = it }, Modifier.fillMaxWidth(), label = { Text("SHA-256 from the publisher (optional, to verify the file)") }, singleLine = true)
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let { Text("Notice. $it", color = C.amber) }
        ui.proposal?.let { p ->
            Panel(title = if (p.blocked) "Blocked" else "Proposal", accent = if (p.blocked) C.danger else if (p.warnings.isEmpty()) C.green else C.amber) {
                Text(p.fileName, color = C.text, style = MaterialTheme.typography.titleMedium)
                Muted("From ${p.host}${p.knownPublisher?.let { " · $it" } ?: " · publisher not recognised"}")
                Muted("Size ${p.sizeBytes?.let(::human) ?: "unknown"} · ${p.contentType ?: "unknown type"} · saved to Downloads\\Ciel\\${p.category.folder}")
                p.warnings.forEach { Text("• $it", color = if (it.startsWith("BLOCKED")) C.danger else C.amber) }
                if (!p.blocked) Button(enabled = !ui.busy, modifier = Modifier.padding(top = 8.dp), onClick = {
                    ui.busy = true; ui.error = null
                    graph.scope.launch {
                        runCatching {
                            ui.result = dm.download(p, ui.sha.takeIf { it.isNotBlank() }) { got, total -> ui.progress = "${human(got)}${total?.let { " of ${human(it)}" } ?: ""}" }
                            graph.pc.activity.add("Downloaded ${p.fileName} from ${p.host}", "You approved the download", "approved", true)
                        }.onFailure { ui.error = "Download failed: ${it.message}" }
                        ui.busy = false; ui.progress = null
                    }
                }) { Text("Approve & download") }
                ui.progress?.let { Muted(it) }
            }
        }
        ui.result?.let { r ->
            Panel(title = "Downloaded — not opened", accent = if (r.defenderClean == false || r.hashMatches == false) C.danger else C.green) {
                Text(r.file.absolutePath, color = C.text)
                Muted("${human(r.bytes)} · SHA-256 ${r.sha256}")
                Muted("Hash: ${when (r.hashMatches) { true -> "matches the publisher's ✓"; false -> "DOES NOT MATCH — don't use this file"; null -> "no reference hash given" }}")
                r.signature?.let { Muted("Signature: $it${r.signer?.let { s -> " · $s" } ?: ""}") }
                Muted("Microsoft Defender: ${when (r.defenderClean) { true -> "no threats found"; false -> "THREAT FOUND — Defender will handle it"; null -> "scan not available" }}")
                r.notes.forEach { Muted("• $it") }
                TextButton(onClick = { open(graph, r.file.absolutePath) }) { Text("Show in folder") }
                Muted("Ciel won't install it. If you decide to, open it yourself — Windows will still ask for permission.")
            }
        }
    }
}

// ───────────────────────────── Connections ─────────────────────────────

@Composable
fun ConnectionsScreen(graph: DesktopGraph) {
    val s by graph.settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf("ollama") }
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://localhost:11434") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ConnectResult?>(null) }
    var searchKey by remember { mutableStateOf("") }
    var searchNote by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { H("Connections") }
        val conns = s.providers.groupBy { it.id.substringBefore(':') }
        item {
            Panel(title = "Connected") {
                if (conns.isEmpty()) Muted("Nothing yet. Ollama on this PC is the easiest: free, private, no key.")
                conns.forEach { (id, list) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(list.first().displayName.substringBefore(" ·"), color = C.text)
                            Muted(list.joinToString(" · ") { it.model } + " · " + when (InferenceLocation.of(list.first().baseUrl)) {
                                InferenceLocation.ON_DEVICE -> "on this PC"; InferenceLocation.LOCAL_NETWORK -> "your network"; InferenceLocation.CLOUD -> "online" })
                        }
                        if (s.preferredProviderId?.substringBefore(':') == id) Text("preferred", color = C.accent)
                        else TextButton(onClick = { graph.settings.update { it.copy(preferredProviderId = list.first().id) } }) { Text("Prefer") }
                        TextButton(onClick = {
                            list.forEach { graph.secrets.delete(DesktopGraph.keyName(it.id)) }
                            graph.settings.update { st -> st.copy(providers = st.providers.filter { it.id.substringBefore(':') != id },
                                preferredProviderId = st.preferredProviderId?.takeIf { it.substringBefore(':') != id }) }
                        }) { Text("Remove", color = C.danger) }
                    }
                }
            }
        }
        item {
            Panel(title = "Add") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderPresets.all.forEach { p -> Chip(p.displayName, preset == p.key) {
                        preset = p.key; result = null
                        url = when (p.key) { "ollama" -> "http://localhost:11434"; "nim", "custom" -> ""; else -> url }
                    } }
                }
                val local = preset == "ollama" || preset == "nim" || preset == "custom"
                if (local) OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (preset == "ollama") "Ollama address (this PC: http://localhost:11434)" else "Server address, e.g. http://localhost:1234/v1") })
                if (preset != "ollama") OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), label = { Text(if (local) "API key (optional)" else "API key") })
                ProviderPresets.byKey(preset)?.keyUrl?.takeIf { it.isNotBlank() }?.let { u -> TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u)) } }) { Text("Get a key ↗") } }
                Muted("Keys are encrypted for your Windows account and sent only to the provider they belong to.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy && (key.isNotBlank() || (local && url.isNotBlank())), onClick = {
                        busy = true; result = null
                        scope.launch {
                            val connId = java.util.UUID.randomUUID().toString().take(8)
                            val r = try { graph.connectionWizard.connect(key, connId, preset, url.takeIf { local }) { st -> status = st } }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ConnectResult.Failure("Connection failed.", e.message ?: "Unknown error") }
                            if (r is ConnectResult.Success) {
                                if (key.isNotBlank()) r.configs.forEach { c -> graph.secrets.put(DesktopGraph.keyName(c.id), key) }
                                graph.settings.update { st -> st.copy(providers = st.providers + r.configs, preferredProviderId = st.preferredProviderId ?: r.configs.first().id) }
                                key = ""
                            }
                            result = r; busy = false; status = null
                        }
                    }) { Text("Connect") }
                    if (busy) Muted("  ${status ?: "Connecting…"}")
                }
                when (val r = result) {
                    is ConnectResult.Success -> { Text(r.message, color = C.green); r.details.forEach { Muted(it) } }
                    is ConnectResult.NeedsProvider -> Text(r.reason, color = C.amber)
                    is ConnectResult.Failure -> { Text(r.message, color = C.danger); Muted("How to fix: ${r.howToFix}") }
                    null -> Unit
                }
            }
        }
        item {
            Panel(title = "Internet & research") {
                Muted("Built-in search (DuckDuckGo + Wikipedia) works without a key. For better results add one of these.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchProviderKind.values().forEach { k -> Chip(k.label, (s.searchKind ?: SearchProviderKind.BUILT_IN) == k) { graph.settings.update { it.copy(searchKind = k) }; searchNote = null } }
                }
                val kind = s.searchKind ?: SearchProviderKind.BUILT_IN
                if (kind.needsKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(searchKey, { searchKey = it.trim() }, Modifier.weight(1f), singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            label = { Text(if (graph.secrets.get("search:key:${kind.name}") != null) "Key saved — paste to replace" else "${kind.label} key") })
                        TextButton(enabled = searchKey.isNotBlank(), onClick = { graph.secrets.put("search:key:${kind.name}", searchKey); searchKey = ""; searchNote = "Saved." }) { Text("Save") }
                    }
                }
                searchNote?.let { Muted(it) }
            }
        }
    }
}
