package dev.ciel.skills

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.JsonValue
import dev.ciel.task.ActionOutcome
import dev.ciel.task.DeviceAction
import dev.ciel.task.GlobalActionType
import dev.ciel.task.ScrollDirection
import kotlinx.coroutines.delay

/**
 * Skills installed at runtime from a JSON file — no rebuild needed. A declarative skill is a
 * named, parameterised sequence of device steps (a macro). It can only use the same guarded
 * device actions as everything else, so it cannot exceed Ciel's permissions or skip confirmations.
 *
 * Format:
 * {
 *   "id": "night_mode", "name": "Night mode", "description": "...",
 *   "tools": [{
 *     "name": "night_mode_on", "description": "...",
 *     "params": {"level": "Brightness 0-100"},
 *     "steps": [
 *       {"do": "open_app", "package": "com.android.settings"},
 *       {"do": "tap_text", "text": "Display"},
 *       {"do": "wait", "ms": 800},
 *       {"do": "expect_text", "text": "Dark theme"},
 *       {"do": "type", "text": "{{level}}"},
 *       {"do": "back"}
 *     ]
 *   }]
 * }
 */
class DeclarativeSkill private constructor(
    override val id: String,
    override val displayName: String,
    override val description: String,
    private val definitions: Map<String, List<JsonObject>>,
    override val tools: List<ToolSpec>,
    val sourceJson: String,
) : Skill {
    override val requiredCapabilities = setOf(Capability.ACCESSIBILITY)
    override val worksOffline = true

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val steps = definitions[tool] ?: return ToolOutcome.fail("Unknown tool $tool")
        val device = ctx.device ?: return ToolOutcome.fail("Device control unavailable.")
        val log = StringBuilder()
        for ((index, raw) in steps.withIndex()) {
            val step = substitute(raw, args)
            val op = step["do"]?.string ?: return ToolOutcome.fail("Step ${index + 1} has no 'do'.")
            if (op == "wait") { delay((step["ms"]?.long ?: 500L).coerceIn(0, 10_000)); continue }
            if (op == "expect_text") {
                val text = step["text"]?.string ?: ""
                val screen = device.observe(ctx.generation)
                if (screen.nodes.none { it.label.contains(text, ignoreCase = true) }) {
                    return ToolOutcome.fail("Step ${index + 1}: expected \"$text\" on screen but it is not there. Completed so far:\n$log")
                }
                log.append("✓ found \"$text\"\n"); continue
            }
            val action = toAction(op, step) ?: return ToolOutcome.fail("Step ${index + 1}: unknown operation '$op'.")
            val screen = runCatching { device.observe(ctx.generation) }.getOrNull()
            when (val o = device.perform(ctx.taskId, ctx.generation, action, screen)) {
                is ActionOutcome.Done -> log.append("✓ ${action.describe()}\n")
                is ActionOutcome.Failed -> return ToolOutcome.fail("Step ${index + 1} (${action.describe()}) failed: ${o.reason}\n$log")
                is ActionOutcome.Unsupported -> return ToolOutcome.fail("Step ${index + 1} unsupported: ${o.reason}")
            }
            delay(350)
        }
        return ToolOutcome.ok("All ${steps.size} steps performed:\n$log", verified = steps.any { it["do"]?.string == "expect_text" })
    }

    private fun toAction(op: String, s: JsonObject): DeviceAction? = when (op) {
        "open_app" -> s["package"]?.string?.let { DeviceAction.OpenApp(it) }
        "tap_text" -> s["text"]?.string?.let { DeviceAction.ClickText(it) }
        "tap" -> { val x = s["x"]?.int; val y = s["y"]?.int; if (x != null && y != null) DeviceAction.TapAt(x, y) else null }
        "swipe" -> DeviceAction.Swipe(s["x1"]?.int ?: 0, s["y1"]?.int ?: 0, s["x2"]?.int ?: 0, s["y2"]?.int ?: 0)
        "type" -> s["text"]?.string?.let { DeviceAction.TypeText(null, it) }
        "scroll" -> DeviceAction.Scroll(runCatching { ScrollDirection.valueOf((s["direction"]?.string ?: "down").uppercase()) }.getOrDefault(ScrollDirection.DOWN))
        "back" -> DeviceAction.Global(GlobalActionType.BACK)
        "home" -> DeviceAction.Global(GlobalActionType.HOME)
        "open_url" -> s["url"]?.string?.takeIf { it.startsWith("https://") }?.let { DeviceAction.OpenUrl(it) }
        else -> null
    }

    private fun substitute(step: JsonObject, args: JsonObject): JsonObject =
        JsonObject(step.fields.mapValues { (field, v) ->
            val str = v.string
            if (str != null) Json.of(Regex("\\{\\{(\\w+)}}").replace(str) { m ->
                val value = args[m.groupValues[1]]?.let { it.string ?: it.toString() } ?: ""
                // Parameters inserted into URLs are encoded so they cannot alter the URL's structure.
                if (field == "url") java.net.URLEncoder.encode(value, "UTF-8") else value
            }) else v
        })

    companion object {
        private val allowedOps = setOf("open_app", "tap_text", "tap", "swipe", "type", "scroll", "back", "home", "open_url", "wait", "expect_text")

        /** Parses and validates a skill definition. Throws IllegalArgumentException with a clear message. */
        fun parse(jsonText: String): DeclarativeSkill {
            val root = Json.parseOrNull(jsonText)?.obj ?: throw IllegalArgumentException("Skill file is not a JSON object.")
            val id = root["id"]?.string?.takeIf { it.matches(Regex("[a-z0-9_]{2,40}")) }
                ?: throw IllegalArgumentException("'id' must be 2-40 chars of a-z, 0-9, _")
            val name = root["name"]?.string ?: id
            val desc = root["description"]?.string ?: ""
            val toolsJson = root["tools"]?.array ?: throw IllegalArgumentException("'tools' array is required.")
            val defs = LinkedHashMap<String, List<JsonObject>>()
            val specs = toolsJson.map { tv: JsonValue ->
                val t = tv.obj ?: throw IllegalArgumentException("Each tool must be an object.")
                val tname = t["name"]?.string?.takeIf { it.matches(Regex("[a-z0-9_]{2,48}")) }
                    ?: throw IllegalArgumentException("Tool 'name' must be 2-48 chars of a-z, 0-9, _")
                val steps = t["steps"]?.array?.map { it.obj ?: throw IllegalArgumentException("$tname: steps must be objects") }
                    ?: throw IllegalArgumentException("$tname: 'steps' is required")
                require(steps.isNotEmpty() && steps.size <= 60) { "$tname: 1-60 steps allowed" }
                steps.forEachIndexed { i, s ->
                    val op = s["do"]?.string
                    require(op in allowedOps) { "$tname step ${i + 1}: operation '$op' is not allowed" }
                }
                defs[tname] = steps
                val params = t["params"]?.obj?.fields ?: emptyMap()
                ToolSpec(tname, t["description"]?.string ?: name,
                    Schema.obj(emptyList(), *params.map { (k, v) -> k to Schema.str(v.string ?: k) }.toTypedArray()))
            }
            return DeclarativeSkill("user_$id", name, desc, defs, specs, jsonText)
        }
    }
}
