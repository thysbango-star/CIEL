package dev.ciel.engine

import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.json

/** A command understood without any AI model, mapped onto an existing skill tool. */
sealed class LocalIntent {
    object Stop : LocalIntent()
    data class Tool(val tool: String, val args: JsonObject, val spoken: String) : LocalIntent()
}

/**
 * Offline command understanding for the basics: stop, open apps, calculations, reminders,
 * memory and task lists. Deliberately narrow and deterministic — anything else needs a model.
 */
object LocalIntentParser {
    private val wake = Regex("^(hey |ok |okay )?ciel[,.!:]?\\s*", RegexOption.IGNORE_CASE)
    private val stopRx = Regex("^(stop|halt|cancel( everything| all| that)?|abort|emergency stop|stop everything|stop now)[.!]*$", RegexOption.IGNORE_CASE)

    fun stripWakeWord(text: String): String = text.trim().replace(wake, "").trim()

    fun isStopCommand(text: String): Boolean = stopRx.matches(stripWakeWord(text))

    fun parse(raw: String): LocalIntent? {
        val t = stripWakeWord(raw).trimEnd('.', '!', '?')
        if (t.isEmpty()) return null
        if (stopRx.matches(t)) return LocalIntent.Stop

        Regex("^(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE).matchEntire(t)?.let {
            val app = it.groupValues[1]
            return LocalIntent.Tool("open_app", json { "app" to app }, "Opening $app.")
        }
        Regex("^(?:calculate|compute|what(?:'s| is)|how much is|evaluate)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let {
            val expr = it.groupValues[1].replace(Regex("(?i)\\bplus\\b"), "+").replace(Regex("(?i)\\bminus\\b"), "-")
                .replace(Regex("(?i)\\b(times|multiplied by)\\b"), "*").replace(Regex("(?i)\\bdivided by\\b"), "/")
                .replace(Regex("(?i)\\bsquared\\b"), "^2").replace(Regex("(?i)\\bpercent of\\b"), "% *")
                .replace(Regex("(?i)\\bsquare root of\\b"), "sqrt ")
            // Only treat as maths if what remains is mathematical.
            if (expr.matches(Regex("[0-9a-z+\\-*/^%().,\\s]+", RegexOption.IGNORE_CASE)) && expr.any { c -> c.isDigit() } &&
                expr.replace(Regex("(?i)sqrt|sin|cos|tan|log|ln|pi|abs|e"), "").none { c -> c.isLetter() }) {
                return LocalIntent.Tool("calculate", json { "expression" to expr.trim() }, "")
            }
        }
        Regex("^remind me (?:in )?(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let {
            val n = it.groupValues[1].toInt()
            val minutes = if (it.groupValues[2].lowercase().startsWith("h")) n * 60 else n
            return LocalIntent.Tool("set_reminder", json { "text" to it.groupValues[3]; "in_minutes" to minutes }, "")
        }
        Regex("^remind me to (.+?) in (\\d+)\\s*(minutes?|mins?|hours?|hrs?)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let {
            val n = it.groupValues[2].toInt()
            val minutes = if (it.groupValues[3].lowercase().startsWith("h")) n * 60 else n
            return LocalIntent.Tool("set_reminder", json { "text" to it.groupValues[1]; "in_minutes" to minutes }, "")
        }
        Regex("^remember (?:that )?(.+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let {
            return LocalIntent.Tool("remember", json { "content" to it.groupValues[1]; "category" to "preference" }, "")
        }
        Regex("^what do you (?:remember|know) about (.+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let {
            return LocalIntent.Tool("recall", json { "query" to it.groupValues[1] }, "")
        }
        if (t.matches(Regex("(?i)^(list|show)( my)? (tasks|active tasks)$"))) return LocalIntent.Tool("list_tasks", json { }, "")
        if (t.matches(Regex("(?i)^(list|show)( my)? reminders$"))) return LocalIntent.Tool("list_reminders", json { }, "")
        return null
    }
}
