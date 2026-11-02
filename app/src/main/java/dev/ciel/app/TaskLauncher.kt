package dev.ciel.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.ciel.app.platform.Notifications
import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import dev.ciel.game.AutomationPolicy
import dev.ciel.task.ActionOutcome
import dev.ciel.task.AgentOptions
import dev.ciel.task.DeviceAction
import dev.ciel.task.TaskResult
import dev.ciel.task.TaskState
import dev.ciel.task.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the user asked for; kept so a task can be restarted or saved as a template. */
data class TaskSpec(
    val name: String,
    val objective: String,
    val appPackage: String? = null,
    val appLabel: String? = null,
)

/** Autonomy limits from Settings → Tasks. */
data class TaskSettings(
    val maxSteps: Int = 40,
    val timeoutMinutes: Int = 10,
    val notifyOnFinish: Boolean = true,
    val templates: List<TaskSpec> = emptyList(),
)

object TaskSpecCodec {
    fun encode(t: TaskSpec) = json { "name" to t.name; "objective" to t.objective; opt("app", t.appPackage); opt("label", t.appLabel) }
    fun decode(text: String): TaskSpec? = Json.parseOrNull(text)?.let { o ->
        TaskSpec(o["name"]?.string ?: return null, o["objective"]?.string ?: return null, o["app"]?.string, o["label"]?.string)
    }
}

val DEFAULT_TEMPLATES = listOf(
    TaskSpec("Daily rewards", "Collect all available daily login rewards and free gifts, then return to the main screen."),
    TaskSpec("Daily quests", "Open the quest menu, complete the available daily quests, and claim their rewards."),
    TaskSpec("Dark mode", "Turn on dark theme in Display settings.", "com.android.settings", "Settings"),
)

/**
 * Starts objectives from the Tasks tab, voice or text: opens the target app, then hands the
 * objective to the Game Engine (for reviewed games) or the general screen agent, inside the
 * user's step and time limits. Every action still goes through the guarded device layer.
 */
class TaskLauncher(private val graph: AppGraph) {
    private val specs = java.util.concurrent.ConcurrentHashMap<String, TaskSpec>()

    fun specFor(taskId: String): TaskSpec? = specs[taskId]

    /** Returns null if screen control isn't possible yet, with the reason in [blockedReason]. */
    fun blockedReason(): String? = when {
        !graph.guardedDevice.isAvailable -> "Turn on Ciel's screen control first (Settings → Permissions → Screen control)."
        graph.settings.current.providers.none { it.enabled } -> "Connect an AI provider first (Connect tab)."
        else -> null
    }

    fun launch(spec: TaskSpec): String {
        val limits = graph.settings.current.tasks
        val isGame = spec.appPackage?.let { pkg -> runCatching { dev.ciel.app.platform.AndroidInstalledPackages(graph.context).isGame(pkg) }.getOrDefault(false) } == true
        val id = graph.tasks.start(spec.objective, if (isGame) "game" else "execute", title = spec.name, targetApp = spec.appPackage) { handle ->
            withTimeoutOrNull(limits.timeoutMinutes * 60_000L) {
                handle.plan(listOfNotNull(spec.appLabel?.let { "Open $it" }, "Understand the screen", "Work toward the objective", "Verify the result"), spec.objective)
                spec.appPackage?.let { pkg ->
                    handle.step("Opening ${spec.appLabel ?: pkg}", pkg)
                    val o = graph.guardedDevice.perform(handle.id, handle.generation, DeviceAction.OpenApp(pkg), null)
                    if (o !is ActionOutcome.Done) return@withTimeoutOrNull TaskResult(false, "Couldn't open ${spec.appLabel ?: pkg}.", false)
                    handle.markStepDone(0)
                    delay(if (isGame) 4000 else 1500) // let the app load
                }
                val profile = spec.appPackage?.let { graph.gameProfiles.get(it) }
                if (spec.appPackage != null && (isGame || profile != null)) {
                    if (profile == null || profile.policy == AutomationPolicy.UNREVIEWED) {
                        // First time: create the profile and ask the user to review the game's rules.
                        graph.gameEngine.profileFor(spec.appPackage)
                    }
                    graph.gameEngine.runObjective(spec.appPackage, spec.objective, handle, limits.maxSteps)
                } else {
                    val agent = graph.screenAgentOrNull() ?: return@withTimeoutOrNull TaskResult(false, blockedReason() ?: "Screen control unavailable.", false)
                    val goal = if (spec.appLabel != null) "In ${spec.appLabel}: ${spec.objective}" else spec.objective
                    agent.run(handle, goal, AgentOptions(maxSteps = limits.maxSteps, includeScreenshots = graph.settings.current.allowScreenshots)).result
                }
            } ?: TaskResult(false, "Stopped after the ${limits.timeoutMinutes}-minute time limit.", false)
        }
        specs[id] = spec
        return id
    }

    fun restart(taskId: String): String? = specs[taskId]?.let { launch(it) }

    /**
     * Starts the gameplay manager for a game: opens it, then runs the selected tasks in order.
     * The time limit is longer than for single tasks (each task has its own stop rule anyway).
     */
    fun launchQueue(pkg: String, label: String, queue: dev.ciel.game.GameQueue): String =
        graph.tasks.start("Gameplay manager: ${queue.tasks.size} task(s)", "game", title = "$label — gameplay manager", targetApp = pkg) { handle ->
            withTimeoutOrNull(maxOf(30, graph.settings.current.tasks.timeoutMinutes * 3) * 60_000L) {
                handle.step("Opening $label", pkg)
                val o = graph.guardedDevice.perform(handle.id, handle.generation, DeviceAction.OpenApp(pkg), null)
                if (o !is ActionOutcome.Done) return@withTimeoutOrNull TaskResult(false, "Couldn't open $label.", false)
                delay(5000) // let the game load
                graph.gameEngine.runQueue(pkg, queue, handle)
            } ?: TaskResult(false, "Stopped at the time limit.", false)
        }.also { checkEventWarnings(pkg) }

    private val warnedEvents = java.util.Collections.synchronizedSet(HashSet<String>())

    /** One notification per event when it ends within 24 hours (as read from the game). */
    fun checkEventWarnings(pkg: String) {
        graph.appScope.launch {
            val urgent = graph.gameEngine.eventWarnings(pkg)
            for (e in urgent) {
                if (!warnedEvents.add(e.name + "|" + (e.endsAt?.div(3_600_000L) ?: 0))) continue
                if (!graph.capabilities.has(android.Manifest.permission.POST_NOTIFICATIONS) && android.os.Build.VERSION.SDK_INT >= 33) continue
                val ctx = graph.context
                val hours = ((e.remainingMs() ?: 0) / 3_600_000L).coerceAtLeast(0)
                val open = PendingIntent.getActivity(ctx, 11, Intent(ctx, dev.ciel.app.ui.MainActivity::class.java).putExtra(dev.ciel.app.ui.MainActivity.EXTRA_ROUTE, "gamedash/$pkg"), PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(ctx, Notifications.TASKS)
                    .setSmallIcon(R.drawable.ic_ciel_mono).setContentTitle("Notice. Event ending soon")
                    .setContentText("${e.name} ends in about $hours h (as shown in the game).")
                    .setContentIntent(open).setAutoCancel(true).build()
                runCatching { ctx.getSystemService(NotificationManager::class.java).notify(("ev" + e.name).hashCode(), n) }
            }
        }
    }

    /** Posts a notification when a task finishes (if enabled), so the user knows while in another app. */
    fun watchCompletions() {
        graph.appScope.launch {
            val seen = HashSet<String>()
            graph.tasks.tasks.map { list -> list.filter { !it.isActive } }.distinctUntilChanged().collect { finished ->
                finished.filter { seen.add(it.id) && it.status != TaskStatus.CANCELLED }.forEach { notify(it); it.targetApp?.let { p -> if (it.kind == "game") checkEventWarnings(p) } }
            }
        }
    }

    private fun notify(t: TaskState) {
        if (!graph.settings.current.tasks.notifyOnFinish) return
        if (!graph.capabilities.has(android.Manifest.permission.POST_NOTIFICATIONS) && android.os.Build.VERSION.SDK_INT >= 33) return
        val ctx = graph.context
        val title = when (t.status) {
            TaskStatus.COMPLETED -> if (t.verified) "Report. Task complete" else "Report. Task finished (unverified)"
            TaskStatus.STOPPED -> "Notice. Task stopped"
            else -> "Notice. Task incomplete"
        }
        val open = PendingIntent.getActivity(ctx, 9, Intent(ctx, dev.ciel.app.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, Notifications.TASKS)
            .setSmallIcon(R.drawable.ic_ciel_mono).setContentTitle(title)
            .setContentText("${t.name}: ${t.report ?: ""}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${t.name}\n${t.report ?: ""}"))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(t.id.hashCode(), n) }
    }
}
