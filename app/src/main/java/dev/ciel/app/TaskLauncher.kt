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
                    val outcome = graph.gameEngine.play(spec.appPackage, spec.objective, handle, limits.maxSteps)
                    outcome.run?.result ?: TaskResult(false, outcome.message, false)
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

    /** Posts a notification when a task finishes (if enabled), so the user knows while in another app. */
    fun watchCompletions() {
        graph.appScope.launch {
            val seen = HashSet<String>()
            graph.tasks.tasks.map { list -> list.filter { !it.isActive } }.distinctUntilChanged().collect { finished ->
                finished.filter { seen.add(it.id) && it.status != TaskStatus.CANCELLED }.forEach { notify(it) }
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
