package dev.ciel.skills.builtin

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.JsonObject
import dev.ciel.skills.Capability
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.int
import dev.ciel.skills.reqStr
import dev.ciel.skills.str
import dev.ciel.task.ActionOutcome
import dev.ciel.task.AgentOptions
import dev.ciel.task.DeviceAction
import dev.ciel.task.ScreenAgent
import dev.ciel.task.TaskStatus

// ---------------------------------------------------------------------------------------------
// Calculate
// ---------------------------------------------------------------------------------------------
class CalculateSkill : Skill {
    override val id = "calculate"
    override val displayName = "Calculate"
    override val description = "Exact arithmetic and scientific functions, offline."
    override val worksOffline = true
    override val tools = listOf(
        ToolSpec("calculate", "Evaluate a mathematical expression exactly. Use for any arithmetic instead of mental math.",
            Schema.obj(listOf("expression"), "expression" to Schema.str("e.g. (12.5*4)^2 / sqrt(9), 15% * 240, sin(rad(30))"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val expr = args.reqStr("expression")
        return try {
            ToolOutcome.ok("$expr = ${Calculator.format(Calculator.evaluate(expr))}", verified = true)
        } catch (e: Calculator.CalcException) {
            ToolOutcome.fail("Could not evaluate: ${e.message}")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// System: launching apps, settings and web searches through the guarded device layer.
// ---------------------------------------------------------------------------------------------
class SystemSkill : Skill {
    override val id = "system"
    override val displayName = "System"
    override val description = "Open apps, Android settings pages and web searches."
    override val worksOffline = true
    override val tools = listOf(
        ToolSpec("open_app", "Open an installed app by its name or package.",
            Schema.obj(listOf("app"), "app" to Schema.str("App name as shown in the launcher, or package name"))),
        ToolSpec("list_apps", "List installed launchable apps (name → package).", Schema.obj()),
        ToolSpec("open_settings", "Open an Android settings page.",
            Schema.obj(listOf("page"), "page" to Schema.str("Settings page", listOf("main", "wifi", "bluetooth", "display", "sound", "battery", "accessibility", "apps", "notifications", "location", "security", "assistant")))),
        ToolSpec("open_web_search", "Open a web search in the user's browser (for the user to read).",
            Schema.obj(listOf("query"), "query" to Schema.str("Search query"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val device = ctx.device ?: return ToolOutcome.fail("Device control is not available.")
        return when (tool) {
            "list_apps" -> ToolOutcome.ok(device.installedApps().entries.sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key} → ${it.value}" }, true)
            "open_app" -> {
                val query = args.reqStr("app")
                val apps = device.installedApps()
                val pkg = resolveApp(query, apps) ?: return ToolOutcome.fail(
                    "No installed app matches \"$query\". Closest: ${apps.keys.sortedBy { levenshtein(it.lowercase(), query.lowercase()) }.take(3).joinToString()}")
                val outcome = device.perform(ctx.taskId, ctx.generation, DeviceAction.OpenApp(pkg), null)
                verifyForeground(ctx, outcome, pkg, "Opened ${apps.entries.first { it.value == pkg }.key}")
            }
            "open_settings" -> outcomeOf(device.perform(ctx.taskId, ctx.generation, DeviceAction.OpenSettings(args.reqStr("page")), null), "Opened ${args.str("page")} settings")
            "open_web_search" -> {
                val q = java.net.URLEncoder.encode(args.reqStr("query"), "UTF-8")
                outcomeOf(device.perform(ctx.taskId, ctx.generation, DeviceAction.OpenUrl("https://www.google.com/search?q=$q"), null), "Opened a web search in the browser")
            }
            else -> ToolOutcome.fail("Unknown tool $tool")
        }
    }

    private suspend fun verifyForeground(ctx: SkillContext, outcome: ActionOutcome, pkg: String, success: String): ToolOutcome {
        if (outcome !is ActionOutcome.Done) return outcomeOf(outcome, success)
        val device = ctx.device ?: return ToolOutcome.ok("$success (not verified)")
        // Verify by observing the foreground app (requires the accessibility service).
        repeat(6) {
            kotlinx.coroutines.delay(400)
            val snap = runCatching { device.observe(ctx.generation) }.getOrNull() ?: return ToolOutcome.ok("$success (launch sent; could not verify without accessibility access)")
            if (snap.packageName == pkg) return ToolOutcome.ok("$success — verified in foreground.", verified = true)
        }
        return ToolOutcome.ok("$success — launch was sent but the app did not appear in the foreground within 2.4s.")
    }

    companion object {
        fun resolveApp(query: String, apps: Map<String, String>): String? {
            val q = query.trim().lowercase()
            if (apps.containsValue(query.trim())) return query.trim()
            apps.entries.firstOrNull { it.key.lowercase() == q }?.let { return it.value }
            apps.entries.filter { it.key.lowercase().startsWith(q) }.minByOrNull { it.key.length }?.let { return it.value }
            apps.entries.filter { it.key.lowercase().contains(q) }.minByOrNull { it.key.length }?.let { return it.value }
            val best = apps.entries.minByOrNull { levenshtein(it.key.lowercase(), q) } ?: return null
            return if (levenshtein(best.key.lowercase(), q) <= maxOf(2, q.length / 4)) best.value else null
        }

        fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}

internal fun outcomeOf(o: ActionOutcome, success: String): ToolOutcome = when (o) {
    is ActionOutcome.Done -> ToolOutcome.ok(success)
    is ActionOutcome.Failed -> ToolOutcome.fail(o.reason)
    is ActionOutcome.Unsupported -> ToolOutcome.fail(o.reason)
}

// ---------------------------------------------------------------------------------------------
// Execute: multi-step autonomous operation of apps via the ScreenAgent.
// ---------------------------------------------------------------------------------------------
class ExecuteSkill(
    private val agentFactory: () -> ScreenAgent?,
    private val screenshotsAllowed: () -> Boolean = { false },
) : Skill {
    override val id = "execute"
    override val displayName = "Execute"
    override val description = "Operate apps on the user's behalf: read the screen and perform multi-step tasks."
    override val requiredCapabilities = setOf(Capability.ACCESSIBILITY)
    override val tools = listOf(
        ToolSpec("read_screen", "Read the elements currently visible on screen.", Schema.obj()),
        ToolSpec("operate_phone",
            "Start an autonomous multi-step task that operates apps on screen (e.g. 'turn on dark mode in the Settings app'). Runs in the background; returns a task id. Do NOT tell the user it is finished — report that it started and that progress is in the task monitor.",
            Schema.obj(listOf("objective"),
                "objective" to Schema.str("Precise objective including the app and completion condition"),
                "max_steps" to Schema.int("Optional step limit, default 30"))),
        ToolSpec("task_status", "Get the status and report of a task by id.", Schema.obj(listOf("task_id"), "task_id" to Schema.str("Task id"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        when (tool) {
            "read_screen" -> {
                val device = ctx.device ?: return ToolOutcome.fail("Device control unavailable.")
                return ToolOutcome.external(device.observe(ctx.generation).describe(), "screen")
            }
            "operate_phone" -> {
                val agent = agentFactory() ?: return ToolOutcome.fail("Autonomous control needs the accessibility service and a configured AI provider.")
                val objective = args.reqStr("objective")
                val steps = (args.int("max_steps") ?: 30).coerceIn(3, 80)
                val id = ctx.tasks.start(objective, "execute") { handle ->
                    handle.plan(listOf("Observe screen", "Act toward objective", "Verify completion"), objective)
                    agent.run(handle, objective, AgentOptions(maxSteps = steps, includeScreenshots = screenshotsAllowed())).result
                }
                return ToolOutcome.ok("Task started (id $id). It is running in the background — not yet complete.")
            }
            "task_status" -> {
                val t = ctx.tasks.get(args.reqStr("task_id")) ?: return ToolOutcome.fail("No such task.")
                val verified = t.status == TaskStatus.COMPLETED && t.verified
                return ToolOutcome.ok("Status: ${t.status}; step: ${t.currentStep ?: "-"}; verified: $verified; report: ${t.report ?: "(running)"}; errors: ${t.errors.takeLast(3)}")
            }
        }
        return ToolOutcome.fail("Unknown tool $tool")
    }
}

// ---------------------------------------------------------------------------------------------
// Manage: reminders (scheduled by the platform) and task overview.
// ---------------------------------------------------------------------------------------------
data class Reminder(val id: String, val text: String, val triggerAtMillis: Long)

/** Implemented with AlarmManager + notifications on Android. */
interface ReminderScheduler {
    suspend fun schedule(text: String, triggerAtMillis: Long): Reminder
    suspend fun list(): List<Reminder>
    suspend fun cancel(id: String): Boolean
}

class ManageSkill(private val reminders: ReminderScheduler, private val clock: () -> Long = System::currentTimeMillis) : Skill {
    override val id = "manage"
    override val displayName = "Manage"
    override val description = "Reminders, schedules and the task list."
    override val worksOffline = true
    override val requiredCapabilities = setOf(Capability.NOTIFICATIONS_POST)
    override val tools = listOf(
        ToolSpec("set_reminder", "Schedule a reminder notification.",
            Schema.obj(listOf("text"),
                "text" to Schema.str("What to remind the user about"),
                "in_minutes" to Schema.int("Minutes from now"),
                "at_iso" to Schema.str("Absolute local time, ISO-8601 like 2026-11-03T08:30"))),
        ToolSpec("list_reminders", "List upcoming reminders.", Schema.obj()),
        ToolSpec("cancel_reminder", "Cancel a reminder by id.", Schema.obj(listOf("id"), "id" to Schema.str("Reminder id"))),
        ToolSpec("list_tasks", "List Ciel's active and recent tasks.", Schema.obj()),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome = when (tool) {
        "set_reminder" -> {
            val text = args.reqStr("text")
            val at = args.int("in_minutes")?.let { clock() + it * 60_000L }
                ?: args.str("at_iso")?.let { iso ->
                    runCatching { java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                        ?: throw IllegalArgumentException("at_iso must look like 2026-11-03T08:30")
                }
                ?: throw IllegalArgumentException("Provide in_minutes or at_iso")
            if (at <= clock()) ToolOutcome.fail("That time is in the past.")
            else {
                val r = reminders.schedule(text, at)
                val confirmed = reminders.list().any { it.id == r.id }
                ToolOutcome.ok("Reminder ${r.id} set for ${java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}: $text" +
                    if (confirmed) " (confirmed scheduled)" else " (WARNING: could not confirm it was scheduled)", verified = confirmed)
            }
        }
        "list_reminders" -> ToolOutcome.ok(reminders.list().sortedBy { it.triggerAtMillis }.joinToString("\n") {
            "${it.id}: ${java.time.Instant.ofEpochMilli(it.triggerAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()} — ${it.text}"
        }.ifEmpty { "No reminders." }, true)
        "cancel_reminder" -> if (reminders.cancel(args.reqStr("id"))) ToolOutcome.ok("Cancelled.", true) else ToolOutcome.fail("No reminder with that id.")
        "list_tasks" -> ToolOutcome.ok(ctx.tasks.tasks.value.takeLast(10).joinToString("\n") { "${it.id.take(8)} ${it.status} — ${it.objective}" }.ifEmpty { "No tasks." }, true)
        else -> ToolOutcome.fail("Unknown tool $tool")
    }
}

// ---------------------------------------------------------------------------------------------
// Create: documents written to the user's storage.
// ---------------------------------------------------------------------------------------------
/** Implemented with MediaStore (Documents/Ciel) on Android. */
interface DocumentStore {
    /** Returns a user-visible location string, after the file has been written and re-read. */
    suspend fun save(fileName: String, mimeType: String, content: String): String
}

class CreateSkill(private val documents: DocumentStore) : Skill {
    override val id = "create"
    override val displayName = "Create"
    override val description = "Write documents (notes, reports, plans) to Documents/Ciel."
    override val worksOffline = true
    override val tools = listOf(
        ToolSpec("save_document", "Save a text or Markdown document you have written for the user.",
            Schema.obj(listOf("file_name", "content"),
                "file_name" to Schema.str("File name ending in .md or .txt"),
                "content" to Schema.str("Full document content"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val name = args.reqStr("file_name").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val safeName = if (name.endsWith(".md") || name.endsWith(".txt")) name else "$name.md"
        val mime = if (safeName.endsWith(".txt")) "text/plain" else "text/markdown"
        val location = documents.save(safeName, mime, args.reqStr("content"))
        return ToolOutcome.ok("Saved to $location", verified = true)
    }
}
