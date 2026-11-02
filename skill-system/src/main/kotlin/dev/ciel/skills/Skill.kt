package dev.ciel.skills

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.JsonObject
import dev.ciel.task.ConfirmationGate
import dev.ciel.task.GuardedDevice
import dev.ciel.task.TaskManager

/** Android permissions / capabilities a skill needs. Mapped to real permissions in the app. */
enum class Capability {
    INTERNET, ACCESSIBILITY, OVERLAY, MICROPHONE, NOTIFICATIONS_POST, NOTIFICATION_ACCESS,
    CONTACTS, SMS, EXACT_ALARMS, SCREENSHOT,
}

/**
 * Result of a tool call. [content] goes back to the model. [verified] is true only when the
 * effect was confirmed (e.g. a reminder was read back from the scheduler, a page fetched with 200).
 */
data class ToolOutcome(
    val success: Boolean,
    val content: String,
    val verified: Boolean = false,
    /** Content from outside the user (web, screen, notifications) — wrapped as untrusted data. */
    val untrusted: Boolean = false,
    val sourceLabel: String? = null,
) {
    companion object {
        fun ok(content: String, verified: Boolean = false) = ToolOutcome(true, content, verified)
        fun fail(reason: String) = ToolOutcome(false, reason)
        fun external(content: String, source: String) = ToolOutcome(true, content, verified = true, untrusted = true, sourceLabel = source)
    }
}

/** Services available to skills at call time. */
class SkillContext(
    val device: GuardedDevice?,
    val tasks: TaskManager,
    val confirm: ConfirmationGate,
    val isOnline: () -> Boolean,
    /** The task this call belongs to, or "chat" for direct conversation turns. */
    val taskId: String = "chat",
    val generation: Long,
)

interface Skill {
    val id: String
    val displayName: String
    val description: String
    val tools: List<ToolSpec>
    val requiredCapabilities: Set<Capability> get() = emptySet()
    /** True when every tool works without internet. */
    val worksOffline: Boolean get() = false
    suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome
}

/** Small helpers for defining JSON-Schema tool parameters. */
object Schema {
    fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JsonObject>): JsonObject =
        dev.ciel.core.json.json {
            "type" to "object"
            "properties" to JsonObject(props.toMap())
            if (required.isNotEmpty()) "required" to required
        }
    fun str(description: String, enum: List<String>? = null) = dev.ciel.core.json.json {
        "type" to "string"; "description" to description; if (enum != null) "enum" to enum
    }
    fun int(description: String) = dev.ciel.core.json.json { "type" to "integer"; "description" to description }
    fun num(description: String) = dev.ciel.core.json.json { "type" to "number"; "description" to description }
    fun bool(description: String) = dev.ciel.core.json.json { "type" to "boolean"; "description" to description }
}

fun JsonObject.str(key: String): String? = this[key]?.string
fun JsonObject.reqStr(key: String): String = this[key]?.string?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("Missing required argument '$key'")
fun JsonObject.int(key: String): Int? = this[key]?.int ?: this[key]?.string?.toIntOrNull()
fun JsonObject.bool(key: String): Boolean? = this[key]?.bool
