package dev.ciel.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ciel.app.CielApplication
import dev.ciel.app.DEFAULT_TEMPLATES
import dev.ciel.app.TaskSpec
import dev.ciel.app.data.ActionLogEntity
import dev.ciel.app.data.TaskEntity
import dev.ciel.app.ui.components.CielPanel
import dev.ciel.app.ui.theme.CielColors
import dev.ciel.task.TaskState
import dev.ciel.task.TaskStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class TaskFilter(val label: String) { ACTIVE("Active"), COMPLETED("Completed"), FAILED("Failed / stopped"), ALL("History") }

/**
 * Tasks: give Ciel an objective (in any app or a reviewed game) and watch her plan, act, verify
 * and report. Pause, resume, cancel, restart, templates, history and the action log.
 */
@Composable
fun TasksScreen() {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    val live by graph.tasks.tasks.collectAsStateWithLifecycle()
    val history by remember { graph.db.tasks().observe() }.collectAsStateWithLifecycle(emptyList<TaskEntity>())
    val log by remember { graph.db.tasks().observeLog(60) }.collectAsStateWithLifecycle(emptyList<ActionLogEntity>())
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TaskFilter.ACTIVE) }
    var creating by remember { mutableStateOf<TaskSpec?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }
    val active = live.filter { it.isActive }
    // Finished tasks: this session's live states first (they have plans/errors), then stored history.
    val finishedLive = live.filter { !it.isActive }
    val stored = history.filter { h -> live.none { it.id == h.id } }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", style = MaterialTheme.typography.titleLarge, color = CielColors.Text, modifier = Modifier.weight(1f))
                if (active.isNotEmpty()) Button(onClick = { graph.emergencyStop.trigger("stop all tasks") },
                    colors = ButtonDefaults.buttonColors(containerColor = CielColors.Danger)) { Text("Stop all") }
                Button(onClick = { creating = TaskSpec("", "") }, modifier = Modifier.padding(start = 6.dp)) { Text("New task") }
            }
            graph.taskLauncher.blockedReason()?.let { Text("Notice. $it", color = CielColors.Amber, style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            Text("Templates", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(settings.tasks.templates + DEFAULT_TEMPLATES.filter { d -> settings.tasks.templates.none { it.name == d.name } }) { t ->
                    FilterChip(selected = false, onClick = { creating = t }, label = { Text(t.name + (t.appLabel?.let { " · $it" } ?: "")) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TaskFilter.values().forEach { f ->
                    val count = when (f) {
                        TaskFilter.ACTIVE -> active.size
                        TaskFilter.COMPLETED -> finishedLive.count { it.status == TaskStatus.COMPLETED } + stored.count { it.status == "COMPLETED" }
                        TaskFilter.FAILED -> finishedLive.count { it.status != TaskStatus.COMPLETED } + stored.count { it.status != "COMPLETED" }
                        TaskFilter.ALL -> finishedLive.size + stored.size
                    }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($count)") })
                }
            }
        }
        when (filter) {
            TaskFilter.ACTIVE -> {
                if (active.isEmpty()) item { Text("No active tasks. Tap New task, pick a template, or just tell Ciel what to do.", color = CielColors.Muted) }
                items(active, key = { "a" + it.id }) { TaskCard(it) }
            }
            else -> {
                val wantLive = finishedLive.filter { t -> filter == TaskFilter.ALL || (filter == TaskFilter.COMPLETED) == (t.status == TaskStatus.COMPLETED) }
                val wantStored = stored.filter { t -> filter == TaskFilter.ALL || (filter == TaskFilter.COMPLETED) == (t.status == "COMPLETED") }
                if (wantLive.isEmpty() && wantStored.isEmpty()) item { Text("Nothing here yet.", color = CielColors.Muted) }
                items(wantLive.reversed(), key = { "f" + it.id }) { TaskCard(it) }
                items(wantStored, key = { "h" + it.id }) { StoredTaskCard(it) }
            }
        }
        item {
            Row {
                TextButton(onClick = { showLimits = !showLimits }) { Text(if (showLimits) "Hide autonomy settings" else "Autonomy settings") }
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide action log" else "Action log") }
            }
            if (showLimits) CielPanel(title = "Autonomy") {
                Text("Ciel works through a task without asking about each tap. Purchases, sending, deleting and security changes still need your approval.", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
                var steps by remember(settings.tasks.maxSteps) { mutableStateOf(settings.tasks.maxSteps.toFloat()) }
                Text("Maximum steps per task: ${steps.toInt()}", color = CielColors.Text)
                Slider(steps, { steps = it }, valueRange = 10f..120f, onValueChangeFinished = {
                    scope.launch { graph.settings.update { it.copy(tasks = it.tasks.copy(maxSteps = steps.toInt())) } } })
                var minutes by remember(settings.tasks.timeoutMinutes) { mutableStateOf(settings.tasks.timeoutMinutes.toFloat()) }
                Text("Time limit: ${minutes.toInt()} min", color = CielColors.Text)
                Slider(minutes, { minutes = it }, valueRange = 1f..60f, onValueChangeFinished = {
                    scope.launch { graph.settings.update { it.copy(tasks = it.tasks.copy(timeoutMinutes = minutes.toInt())) } } })
                Toggle("Notify me when a task finishes", settings.tasks.notifyOnFinish) { v -> scope.launch { graph.settings.update { it.copy(tasks = it.tasks.copy(notifyOnFinish = v)) } } }
                Text("Emergency stop: \"Ciel, stop\", the floating STOP button, double-tap the floating crystal, or Stop all.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showLog) items(log, key = { "l" + it.id }) { a ->
            Text("${DateFormat.getTimeInstance().format(Date(a.timestamp))} · ${a.action} · ${a.risk.lowercase()}${a.approved?.let { if (it) " · approved" else " · declined" } ?: ""} → ${a.outcome}",
                color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }

    creating?.let { initial -> NewTaskDialog(initial, onDismiss = { creating = null }) }
}

@Composable
private fun statusColor(s: TaskStatus) = when (s) {
    TaskStatus.COMPLETED -> CielColors.Green
    TaskStatus.RUNNING, TaskStatus.PLANNING -> CielColors.Cyan
    TaskStatus.QUEUED, TaskStatus.PAUSED, TaskStatus.WAITING_FOR_USER -> CielColors.Amber
    else -> CielColors.Danger
}

@Composable
private fun TaskCard(t: TaskState) {
    val graph = CielApplication.graph
    var expanded by remember { mutableStateOf(t.isActive) }
    CielPanel(accent = statusColor(t.status).copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Text(t.name, color = CielColors.Text, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(t.status.name.lowercase().replace('_', ' ') + if (t.verified) " · verified" else "",
                t.currentApp ?: t.targetApp, "started " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.startedAt))).joinToString(" · "),
                color = statusColor(t.status), style = MaterialTheme.typography.labelSmall)
            if (t.isActive) {
                t.currentStep?.let { Text("Now: $it", color = CielColors.Text, style = MaterialTheme.typography.bodyMedium) }
                val p = t.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                else if (t.status == TaskStatus.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
        if (expanded) {
            if (t.title != null && t.title != t.objective) Text("Objective: ${t.objective}", color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium)
            if (t.plan.isNotEmpty()) {
                Text("Plan", color = CielColors.Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                t.plan.forEach { s -> Text((if (s.done) "✓ " else "○ ") + s.description, color = if (s.done) CielColors.Green else CielColors.Text, style = MaterialTheme.typography.bodyMedium) }
            }
            t.report?.let { Text("Result: $it", color = CielColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            t.errors.takeLast(3).forEach { Text("⚠ $it", color = CielColors.Amber, style = MaterialTheme.typography.labelSmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (t.isActive) {
                if (t.status == TaskStatus.PAUSED) TextButton(onClick = { graph.tasks.resume(t.id) }) { Text("Resume") }
                else TextButton(onClick = { graph.tasks.pause(t.id) }) { Text("Pause") }
                TextButton(onClick = { graph.tasks.cancel(t.id) }) { Text("Cancel", color = CielColors.Danger) }
            } else {
                if (graph.taskLauncher.specFor(t.id) != null) TextButton(onClick = { graph.taskLauncher.restart(t.id) }) { Text("Run again") }
            }
        }
    }
}

@Composable
private fun StoredTaskCard(t: TaskEntity) {
    val graph = CielApplication.graph
    val ok = t.status == "COMPLETED"
    CielPanel(accent = (if (ok) CielColors.Green else CielColors.Amber).copy(alpha = 0.5f)) {
        Text(t.objective, color = CielColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 3)
        Text("${t.status.lowercase()}${if (t.verified) " · verified" else ""} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.startedAt))}",
            color = if (ok) CielColors.Green else CielColors.Amber, style = MaterialTheme.typography.labelSmall)
        t.report?.let { Text(it, color = CielColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { graph.taskLauncher.launch(TaskSpec(t.objective.take(40), t.objective)) }) { Text("Run again") }
    }
}

/** Create a task: name, objective, optional app or game, save as template. */
@Composable
private fun NewTaskDialog(initial: TaskSpec, onDismiss: () -> Unit) {
    val graph = CielApplication.graph
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var objective by remember { mutableStateOf(initial.objective) }
    var app by remember { mutableStateOf(initial.appPackage?.let { it to (initial.appLabel ?: it) }) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var appMenu by remember { mutableStateOf(false) }
    var saveTemplate by remember { mutableStateOf(false) }
    val blocked = graph.taskLauncher.blockedReason()
    LaunchedEffect(Unit) {
        // Games first, then other apps, alphabetically.
        val pk = dev.ciel.app.platform.AndroidInstalledPackages(graph.context)
        apps = graph.guardedDevice.installedApps().entries.map { it.value to it.key }
            .sortedWith(compareBy<Pair<String, String>> { !pk.isGame(it.first) }.thenBy { it.second.lowercase() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(objective, { objective = it }, label = { Text("Objective") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Complete today's daily quests and claim the rewards") })
                Box {
                    OutlinedButton(onClick = { appMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(app?.second?.let { "App: $it" } ?: "Choose an app or game (optional)") }
                    DropdownMenu(appMenu, { appMenu = false }) {
                        DropdownMenuItem(text = { Text("No specific app") }, onClick = { app = null; appMenu = false })
                        apps.take(200).forEach { (pkg, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { app = pkg to label; appMenu = false }) }
                    }
                }
                Text("Games must be reviewed once in Settings → Games before Ciel plays them; single-player only.", color = CielColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saveTemplate, { saveTemplate = it }); Text("Save as a template", color = CielColors.Text)
                }
                blocked?.let { Text("Notice. $it", color = CielColors.Amber) }
            }
        },
        confirmButton = {
            Button(enabled = objective.isNotBlank() && blocked == null, onClick = {
                val spec = TaskSpec(name.ifBlank { objective.take(40) }, objective.trim(), app?.first, app?.second)
                if (saveTemplate) scope.launch { graph.settings.update { s -> s.copy(tasks = s.tasks.copy(templates = (s.tasks.templates.filter { it.name != spec.name } + spec).takeLast(20))) } }
                graph.taskLauncher.launch(spec)
                onDismiss()
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
