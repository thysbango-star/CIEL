package dev.ciel.skills

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SkillStatus(val skill: Skill, val enabled: Boolean, val missingCapabilities: Set<Capability>)

/**
 * Holds every skill, which are enabled, and routes tool calls. Tool names are namespaced
 * internally so two skills cannot collide.
 */
class SkillRegistry(private val grantedCapabilities: () -> Set<Capability>) {
    private val skills = LinkedHashMap<String, Skill>()
    private val enabled = HashMap<String, Boolean>()
    private val _status = MutableStateFlow<List<SkillStatus>>(emptyList())
    val status: StateFlow<List<SkillStatus>> = _status.asStateFlow()

    @Synchronized
    fun register(skill: Skill, enabledByDefault: Boolean = true) {
        val clash = skill.tools.map { it.name }.firstOrNull { name -> skills.values.any { s -> s.id != skill.id && s.tools.any { it.name == name } } }
        require(clash == null) { "Tool name '$clash' is already provided by another skill." }
        skills[skill.id] = skill
        enabled.putIfAbsent(skill.id, enabledByDefault)
        refresh()
    }

    @Synchronized
    fun unregister(skillId: String) { skills.remove(skillId); enabled.remove(skillId); refresh() }

    @Synchronized
    fun setEnabled(skillId: String, value: Boolean) { if (skillId in skills) { enabled[skillId] = value; refresh() } }

    fun refresh() {
        val granted = grantedCapabilities()
        _status.value = skills.values.map { SkillStatus(it, enabled[it.id] == true, it.requiredCapabilities - granted) }
    }

    /** Tools offered to the model: enabled skills only, optionally only offline-capable ones. */
    fun availableTools(online: Boolean): List<ToolSpec> = activeSkills(online).flatMap { it.tools }

    fun activeSkills(online: Boolean): List<Skill> = synchronized(this) {
        skills.values.filter { enabled[it.id] == true && (online || it.worksOffline) }
    }

    fun skillForTool(toolName: String): Skill? = synchronized(this) {
        skills.values.firstOrNull { s -> enabled[s.id] == true && s.tools.any { it.name == toolName } }
    }

    suspend fun dispatch(toolName: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        val skill = skillForTool(toolName) ?: return ToolOutcome.fail("No enabled skill provides '$toolName'.")
        val missing = skill.requiredCapabilities - grantedCapabilities()
        if (missing.isNotEmpty()) {
            return ToolOutcome.fail("${skill.displayName} needs permission: ${missing.joinToString { it.name.lowercase().replace('_', ' ') }}. Grant it in Ciel → Permissions.")
        }
        return try {
            skill.invoke(toolName, args, ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            ToolOutcome.fail("Invalid arguments: ${e.message}")
        } catch (e: Exception) {
            ToolOutcome.fail("${skill.displayName} failed: ${e.message ?: e::class.simpleName}")
        }
    }

    fun all(): List<Skill> = synchronized(this) { skills.values.toList() }
}
