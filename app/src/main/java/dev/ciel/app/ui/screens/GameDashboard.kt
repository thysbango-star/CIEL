@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ciel.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.components.Spacing
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.game.AutomationPolicy
import dev.ciel.game.EventInfo
import dev.ciel.game.GameActivity
import dev.ciel.game.GameProfile
import dev.ciel.game.GameQueue
import dev.ciel.game.QueuePlanner
import dev.ciel.game.QueuePreset
import dev.ciel.game.QueueTask
import dev.ciel.game.StopKind
import dev.ciel.game.TaskCategory
import dev.ciel.game.TaskType
import dev.ciel.game.Urgency
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gameplay manager for one game: event priority at the top, the task library by category, the
 * queue (drag to reorder, per-task stop rules), presets, Start, and the live state of a run.
 */
@Composable
fun GameDashboard(pkg: String) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val profiles by remember { graph.gameProfiles.observe() }.collectAsStateWithLifecycle(emptyList<GameProfile>())
    val activity by graph.gameEngine.activity.collectAsStateWithLifecycle()
    val p = profiles.firstOrNull { it.packageName == pkg }
    LaunchedEffect(pkg) { if (graph.gameProfiles.get(pkg) == null) graph.gameEngine.profileFor(pkg); graph.taskLauncher.checkEventWarnings(pkg) }
    if (p == null) { Text("Loading…", Modifier.padding(Spacing.l), color = CielColors.Muted); return }
    fun saveQueue(q: GameQueue) = scope.launch { graph.gameProfiles.get(pkg)?.let { graph.gameProfiles.save(it.copy(queue = q, updatedAt = System.currentTimeMillis())) } }
    fun savePresets(list: List<QueuePreset>) = scope.launch { graph.gameProfiles.get(pkg)?.let { graph.gameProfiles.save(it.copy(presets = list, updatedAt = System.currentTimeMillis())) } }
    val q = p.queue
    val allowed = p.policy == AutomationPolicy.ALLOWED_SINGLE_PLAYER || p.policy == AutomationPolicy.MENUS_ONLY
    val running = activity?.let { it.status == GameActivity.Status.RUNNING || it.status == GameActivity.Status.WAITING || it.status == GameActivity.Status.PAUSED } == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(p.name, style = MaterialTheme.typography.titleLarge, color = CielColors.Text)
        Text("Choose what Ciel should do, put it in order, press Start. She works it out in the game.", color = CielColors.Muted)

        EventPriority(p.events, q) { saveQueue(it) }
        activity?.takeIf { it.game == p.name }?.let { LiveRun(it) }

        CielPanel(title = "Queue") {
            if (q.tasks.isEmpty()) Text("Nothing selected yet. Add tasks from the library below, or load a preset.", color = CielColors.Muted)
            QueueEditor(q.tasks) { saveQueue(q.copy(tasks = it)) }
            if (q.tasks.isNotEmpty()) {
                val plan = QueuePlanner.order(q, p.events)
                Text("Ciel will run: " + plan.tasks.joinToString(" → ") { it.label }, color = CielColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.s))
                (plan.reasons + plan.recommendations).forEach { Text("• $it", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium) }
            }
            SwitchLine("Lock my order", "Ciel follows it strictly and only suggests changes.", q.lockedOrder) { saveQueue(q.copy(lockedOrder = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
                Button(enabled = allowed && q.tasks.any { !it.paused } && !running, onClick = {
                    graph.taskLauncher.launchQueue(pkg, p.name, q)
                }, modifier = Modifier.weight(1f)) { Text(if (running) "Running…" else "Start") }
                if (q.tasks.isNotEmpty()) OutlinedButton(onClick = { saveQueue(q.copy(tasks = emptyList())) }) { Text("Clear") }
            }
            if (!allowed) Text("Allow automation for this game in Settings → Games first.", color = CielColors.Amber)
        }

        Presets(p.presets, q, onLoad = { saveQueue(it.queue.copy(tasks = it.queue.tasks.map { t -> t.copy(id = java.util.UUID.randomUUID().toString().take(8)) })) }, onSave = { savePresets(it) })

        CielPanel(title = "Task library") {
            Text("Activities are confirmed as Ciel finds them in your game version.", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
            TaskCategory.values().forEach { cat ->
                val types = TaskType.values().filter { it.category == cat }
                if (types.isNotEmpty()) Category(cat, types, p) { t -> saveQueue(q.copy(tasks = q.tasks + QueueTask(type = t, resource = if (t == TaskType.FARM) "skill powder" else null))) }
            }
            TextButton(onClick = { saveQueue(q.copy(tasks = q.tasks + TaskType.values().filter { it != TaskType.FARM && it != TaskType.DISCOVER }.map { QueueTask(type = it) })) }) { Text("Add every activity") }
        }
        WhereAiRuns()
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = CielColors.Text)
            Text(subtitle, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun EventPriority(events: List<EventInfo>, q: GameQueue, onQueue: (GameQueue) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    CielPanel(title = "Event priority", accent = if (events.any { it.urgency(now) == Urgency.HIGH }) CielColors.Amber else CielColors.PanelBorder) {
        val live = events.filter { it.urgency(now) != Urgency.ENDED }.sortedBy { it.endsAt ?: Long.MAX_VALUE }
        if (live.isEmpty()) {
            Text("No events read yet. Add \"Check active events\" to the queue; Ciel reads each event's time left from the game.", color = CielColors.Muted)
        } else live.forEach { e ->
            val u = e.urgency(now)
            val color = when (u) { Urgency.HIGH -> CielColors.Amber; Urgency.MEDIUM -> CielColors.Cyan; else -> CielColors.Muted }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, color = CielColors.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(u.label, color = color, style = MaterialTheme.typography.labelSmall)
                }
                Text(listOfNotNull(e.remainingMs(now)?.let { "≈ ${countdown(it)} left" }, e.shownText?.let { "game showed \"$it\" ${ago(now - e.observedAt)}" },
                    e.dailyAttempts?.let { "attempts $it" }, "rewards ready".takeIf { e.claimable }).joinToString(" · "),
                    color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("Next: " + when {
                    e.claimable -> "claim the free rewards"
                    u == Urgency.HIGH -> "complete its objectives before it ends"
                    else -> "include it in today's run"
                }, color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SwitchLine("Automatically prioritise time-limited events", "Events run before ordinary tasks unless the order is locked.", q.prioritiseEvents) { onQueue(q.copy(prioritiseEvents = it)) }
        SwitchLine("Protect daily event attempts", "Use limited daily attempts before they reset.", q.protectDailyAttempts) { onQueue(q.copy(protectDailyAttempts = it)) }
    }
}

private fun countdown(ms: Long): String {
    val m = ms / 60_000; val d = m / 1440; val h = (m % 1440) / 60; val mm = m % 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${mm}m" else "${mm}m"
}
private fun ago(ms: Long) = (ms / 60_000).let { if (it < 60) "${it}m ago" else "${it / 60}h ago" }

@Composable
private fun LiveRun(a: GameActivity) {
    val graph = CielApplication.graph
    CielPanel(title = "Now", accent = CielColors.Cyan) {
        Text("${a.status.label} · ${a.currentAction}", color = CielColors.Text)
        a.nextAction?.let { Text("Next: $it", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        a.queue.forEach { item ->
            Text("${item.state.symbol}  ${item.label}" + (item.note?.let { " — $it" } ?: ""),
                color = when (item.state) { GameActivity.ItemState.RUNNING -> CielColors.Cyan; GameActivity.ItemState.DONE -> CielColors.Green; GameActivity.ItemState.BLOCKED -> CielColors.Amber; else -> CielColors.Muted })
        }
        Text("${a.usage.mode} · on the phone: ${a.usage.localDecisions} · AI: ${a.usage.aiCalls}", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
        val active = a.status == GameActivity.Status.RUNNING || a.status == GameActivity.Status.WAITING || a.status == GameActivity.Status.PAUSED
        if (active) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { graph.messageGameTask(if (a.status == GameActivity.Status.PAUSED) "continue" else "pause") }) { Text(if (a.status == GameActivity.Status.PAUSED) "Resume" else "Pause") }
            OutlinedButton(onClick = { graph.gameEngine.skipCurrentTask(a.taskId) }) { Text("Skip task") }
            Button(onClick = { graph.emergencyStop.trigger("dashboard stop") }, colors = ButtonDefaults.buttonColors(containerColor = CielColors.Danger)) { Text("Stop") }
        }
        a.finalReport?.let { Text(it, color = CielColors.Text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Category(cat: TaskCategory, types: List<TaskType>, p: GameProfile, onAdd: (TaskType) -> Unit) {
    var open by rememberSaveable(cat.name) { mutableStateOf(cat == TaskCategory.EVENTS || cat == TaskCategory.DAILY) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cat.title, color = CielColors.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = CielColors.Muted)
        }
        AnimatedVisibility(open, enter = fadeIn(tween(200)) + expandVertically(tween(240)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t ->
                    val inQueue = p.queue.tasks.any { it.type == t }
                    Column(Modifier.fillMaxWidth().background(CielColors.Panel, RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Text(t.title, color = CielColors.Text)
                        Text(t.description, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(QueuePlanner.availability(t, p.knowledge), color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onAdd(t) }) { Text(if (inQueue && t != TaskType.FARM) "Add again" else "Add") }
                        }
                    }
                }
            }
        }
    }
}

/** The queue: long-press and drag to reorder (arrows for accessibility), per-task settings. */
@Composable
private fun QueueEditor(tasks: List<QueueTask>, onChange: (List<QueueTask>) -> Unit) {
    var list by remember(tasks) { mutableStateOf(tasks) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    var editing by remember { mutableStateOf<String?>(null) }
    fun swap(i: Int, j: Int) { list = list.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x } }
    Column(Modifier.animateContentSize(tween(200)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEachIndexed { index, t ->
            val dragging = dragId == t.id
            Column(
                Modifier.fillMaxWidth()
                    .onGloballyPositioned { heights[t.id] = it.size.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f; shadowElevation = if (dragging) 12f else 0f }
                    .background(if (dragging) CielColors.Cyan.copy(alpha = 0.15f) else CielColors.Panel, RoundedCornerShape(12.dp))
                    .border(1.dp, if (dragging) CielColors.Cyan else CielColors.PanelBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DragHandle, "Hold and drag to reorder", tint = CielColors.Muted, modifier = Modifier.pointerInput(t.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragId = t.id; offset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.y
                                val i = list.indexOfFirst { it.id == dragId }
                                if (i < 0) return@detectDragGesturesAfterLongPress
                                val gap = 6.dp.toPx()
                                if (offset > 0 && i < list.lastIndex) {
                                    val h = heights[list[i + 1].id] ?: return@detectDragGesturesAfterLongPress
                                    if (offset > h / 2f) { swap(i, i + 1); offset -= h + gap }
                                } else if (offset < 0 && i > 0) {
                                    val h = heights[list[i - 1].id] ?: return@detectDragGesturesAfterLongPress
                                    if (offset < -h / 2f) { swap(i, i - 1); offset += h + gap }
                                }
                            },
                            onDragEnd = { dragId = null; offset = 0f; onChange(list) },
                            onDragCancel = { dragId = null; offset = 0f; onChange(list) },
                        )
                    })
                    Text("${index + 1}. ${t.label}", color = if (t.paused) CielColors.Muted else CielColors.Text, modifier = Modifier.weight(1f).padding(start = 8.dp).clickable { editing = if (editing == t.id) null else t.id })
                    IconButton(enabled = index > 0, onClick = { swap(index, index - 1); onChange(list) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(enabled = index < list.lastIndex, onClick = { swap(index, index + 1); onChange(list) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { onChange(list.filter { it.id != t.id }) }) { Icon(Icons.Filled.Close, "Remove", tint = CielColors.Danger) }
                }
                Text(t.stop.label + (if (t.stop.needsNumber) ": ${t.number}" else "") + (if (t.paused) " · paused" else "") + " · tap to edit",
                    color = CielColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 32.dp))
                AnimatedVisibility(editing == t.id) {
                    TaskSettings(t) { updated -> onChange(list.map { if (it.id == t.id) updated else it }) }
                }
            }
        }
    }
}

@Composable
private fun TaskSettings(t: QueueTask, onChange: (QueueTask) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.type.stops.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            t.type.stops.forEach { s -> FilterChip(selected = t.stop == s, onClick = { onChange(t.copy(stop = s)) }, label = { Text(s.label) }) }
        }
        if (t.stop == StopKind.RUNS) {
            var n by remember(t.number) { mutableStateOf(t.number.toString()) }
            OutlinedTextField(n, { v -> n = v.filter(Char::isDigit).take(2); n.toIntOrNull()?.let { onChange(t.copy(number = it.coerceIn(1, 50))) } },
                label = { Text("Runs (1–50)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp))
        }
        if (t.type == TaskType.FARM) {
            var r by remember(t.resource) { mutableStateOf(t.resource ?: "") }
            OutlinedTextField(r, { r = it; onChange(t.copy(resource = it.ifBlank { null })) }, label = { Text("Resource to farm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("skill powder", "experience", "coins", "soulstones", "materials").forEach { s -> AssistChip(onClick = { r = s; onChange(t.copy(resource = s)) }, label = { Text(s) }) }
            }
        }
        SwitchLine("Pause this task", "Kept in the queue but skipped.", t.paused) { onChange(t.copy(paused = it)) }
    }
}

@Composable
private fun Presets(presets: List<QueuePreset>, q: GameQueue, onLoad: (QueuePreset) -> Unit, onSave: (List<QueuePreset>) -> Unit) {
    var naming by remember { mutableStateOf<QueuePreset?>(null) } // null name = new preset
    var newName by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<QueuePreset?>(null) }
    CielPanel(title = "Presets") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (QueuePlanner.DEFAULT_PRESETS + presets).forEach { pr ->
                val mine = pr in presets
                AssistChip(onClick = { if (mine) manage = pr else onLoad(pr) }, label = { Text(pr.name + if (mine) " ⋯" else "") })
            }
        }
        TextButton(enabled = q.tasks.isNotEmpty(), onClick = { newName = true }) { Text("Save this queue as a preset") }
    }
    if (newName || naming != null) {
        var text by remember { mutableStateOf(naming?.name ?: "") }
        AlertDialog(onDismissRequest = { newName = false; naming = null }, title = { Text(if (naming != null) "Rename preset" else "Save preset") },
            text = { OutlinedTextField(text, { text = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
                val old = naming
                onSave(if (old != null) presets.map { if (it == old) it.copy(name = text.trim()) else it } else presets.filter { it.name != text.trim() } + QueuePreset(text.trim(), q))
                newName = false; naming = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { newName = false; naming = null }) { Text("Cancel") } })
    }
    manage?.let { pr ->
        AlertDialog(onDismissRequest = { manage = null }, title = { Text(pr.name) },
            text = { Text(pr.queue.tasks.joinToString("\n") { "• " + it.label }) },
            confirmButton = { TextButton(onClick = { onLoad(pr); manage = null }) { Text("Load") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { naming = pr; manage = null }) { Text("Rename") }
                    TextButton(onClick = { onSave(presets - pr); manage = null }) { Text("Delete", color = CielColors.Danger) }
                }
            })
    }
}

/** Where each connected model runs — so "offline" is never claimed for a PC or cloud model. */
@Composable
private fun WhereAiRuns() {
    val settings by CielApplication.graph.settings.flow.collectAsStateWithLifecycle()
    CielPanel(title = "Who decides") {
        Text("Routine game actions (claiming, closing pop-ups, known routes, results) are decided on the phone. The AI model is asked only for unfamiliar screens and judgement calls; if it's unavailable, Ciel keeps going with what she can do locally.",
            color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
        val providers = settings.providers.filter { it.enabled }
        if (providers.isEmpty()) Text("No AI model connected: only on-phone decisions.", color = CielColors.Amber)
        providers.forEach { c ->
            Text("• ${c.displayName}: ${c.model} — runs ${dev.ciel.ai.InferenceLocation.of(c.baseUrl).label}" + if (c.supportsVision) " · sees images" else "",
                color = CielColors.Text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
