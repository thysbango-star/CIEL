package dev.ciel.app

import dev.ciel.app.design.CielDesign
import dev.ciel.app.design.ThemeStyle
import dev.ciel.engine.LocalIntentParser

/** Commands that control Ciel's own interface. Handled instantly, offline, without a model. */
sealed class UiCommand {
    data class Navigate(val route: String, val label: String) : UiCommand()
    data class SetTheme(val theme: ThemeStyle) : UiCommand()
    data class SetLayout(val design: CielDesign) : UiCommand()
    object StopSpeaking : UiCommand()
    object Repeat : UiCommand()
    object Status : UiCommand()
}

object UiCommands {
    private val routes = mapOf(
        "tasks" to "tasks", "task" to "tasks", "chats" to "chats", "conversations" to "chats", "history" to "chats",
        "memory" to "memory", "memories" to "memory", "skills" to "skills", "connections" to "connect", "connect" to "connect",
        "home" to "home", "appearance" to "appearance", "themes" to "designs", "your settings" to "settings", "ciel settings" to "settings",
    )

    private fun norm(s: String) = s.lowercase().replace("'", "").replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun parse(raw: String): UiCommand? {
        val t = norm(LocalIntentParser.stripWakeWord(raw))
        if (t.isEmpty()) return null
        if (t == "go home" || t == "home") return UiCommand.Navigate("home", "Home")
        Regex("^(open|show|go to|show me)( the| my)? (.+)$").matchEntire(t)?.let { m ->
            val what = m.groupValues[3].removeSuffix(" tab").removeSuffix(" page").trim()
            routes[what]?.let { return UiCommand.Navigate(it, what.replaceFirstChar { c -> c.uppercase() }) }
        }
        Regex("^(switch|change|set)( to| the theme to| theme to| layout to)? (.+?)( mode| theme| layout| style)?$").matchEntire(t)?.let { m ->
            val name = m.groupValues[3].trim()
            ThemeStyle.values().firstOrNull { norm(it.title) == name || norm(it.title).replace(" ", "") == name.replace(" ", "") }?.let { return UiCommand.SetTheme(it) }
            if (name == "glass") return UiCommand.SetTheme(ThemeStyle.GLASSMORPHISM)
            CielDesign.values().firstOrNull { norm(it.title) == name }?.let { return UiCommand.SetLayout(it) }
        }
        if (t in setOf("stop talking", "stop speaking", "be quiet", "quiet", "shush", "silence")) return UiCommand.StopSpeaking
        if (t in setOf("repeat", "repeat that", "say that again", "what did you say", "again")) return UiCommand.Repeat
        if (t in setOf("what are you doing", "status", "whats your status", "what are you working on", "report")) return UiCommand.Status
        return null
    }
}
