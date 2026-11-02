package dev.ciel.engine

import dev.ciel.ai.CapabilityTier
import dev.ciel.ai.ProviderConfig

enum class Complexity { SIMPLE, MODERATE, COMPLEX }

data class TaskProfile(
    val complexity: Complexity,
    val needsVision: Boolean,
    val needsTools: Boolean,
    /** Rough token estimate of the prompt (history + memory + message). */
    val estimatedTokens: Int,
    val mode: ReasoningMode,
)

/** Heuristic classification — cheap, offline, deterministic. */
object ComplexityClassifier {
    private val complexWords = Regex("(?i)\\b(analy[sz]e|compare|research|plan|design|strategy|explain why|pros and cons|trade-?offs?|evaluate|report|debug|architecture|essay|summari[sz]e (this|the) (document|article|paper)|step[- ]by[- ]step|investigate)\\b")
    private val actionWords = Regex("(?i)\\b(open|launch|send|message|text|email|remind|set|turn on|turn off|play|collect|tap|scroll|search|find|remember|forget|calculate|schedule|reply)\\b")

    fun classify(message: String, hasImages: Boolean, historyChars: Int, mode: ReasoningMode): TaskProfile {
        val words = message.split(Regex("\\s+")).count { it.isNotBlank() }
        val complexity = when {
            mode == ReasoningMode.ANALYSE || mode == ReasoningMode.RESEARCH || mode == ReasoningMode.PREDICT -> Complexity.COMPLEX
            complexWords.containsMatchIn(message) || words > 120 -> Complexity.COMPLEX
            words > 25 || mode == ReasoningMode.CREATE -> Complexity.MODERATE
            else -> Complexity.SIMPLE
        }
        return TaskProfile(complexity, hasImages, actionWords.containsMatchIn(message) || mode == ReasoningMode.RESEARCH,
            (historyChars + message.length) / 4 + 1500, mode)
    }
}

/** Counts requests per provider per day (persisted by the app). */
interface UsageTracker {
    fun requestsToday(providerId: String): Int
    fun record(providerId: String, success: Boolean, rateLimited: Boolean)
    /** Providers that recently returned 429 are cooled down until this epoch-millis. */
    fun cooldownUntil(providerId: String): Long
}

class InMemoryUsageTracker(private val clock: () -> Long = System::currentTimeMillis) : UsageTracker {
    private val counts = HashMap<String, Pair<Long, Int>>()
    private val cooldowns = HashMap<String, Long>()
    private fun day() = clock() / 86_400_000L
    @Synchronized override fun requestsToday(providerId: String): Int = counts[providerId]?.takeIf { it.first == day() }?.second ?: 0
    @Synchronized override fun record(providerId: String, success: Boolean, rateLimited: Boolean) {
        counts[providerId] = day() to requestsToday(providerId) + 1
        if (rateLimited) cooldowns[providerId] = clock() + 60_000
    }
    @Synchronized override fun cooldownUntil(providerId: String) = cooldowns[providerId] ?: 0L
}

data class Selection(val ordered: List<ProviderConfig>, val reason: String)

/**
 * Chooses which configured provider handles a request and the fallback order.
 * Rules: honour a manual override; exclude providers that can't do the job (vision, tools,
 * context) or are over their user-set daily limit or cooling down after a 429;
 * then match tier to complexity (economy for simple chat, advanced for complex work),
 * preferring free tiers and the user's preferred provider on ties.
 */
class ProviderSelector(private val usage: UsageTracker, private val clock: () -> Long = System::currentTimeMillis) {

    fun select(all: List<ProviderConfig>, task: TaskProfile, preferredId: String?, overrideId: String?, autoSelect: Boolean): Selection {
        val enabled = all.filter { it.enabled }
        if (enabled.isEmpty()) return Selection(emptyList(), "No AI provider is configured.")
        if (overrideId != null) {
            enabled.firstOrNull { it.id == overrideId }?.let { o ->
                return Selection(listOf(o) + enabled.filter { it.id != o.id && eligible(it, task) }, "Manual choice: ${o.displayName}")
            }
        }
        if (!autoSelect) {
            val pref = enabled.firstOrNull { it.id == preferredId } ?: enabled.first()
            return Selection(listOf(pref) + enabled.filter { it.id != pref.id && eligible(it, task) }, "Preferred provider: ${pref.displayName}")
        }
        val candidates = enabled.filter { eligible(it, task) }
        if (candidates.isEmpty()) {
            val why = when {
                task.needsVision && enabled.none { it.supportsVision } -> "No configured model supports images. Mark a vision-capable model in the provider settings."
                enabled.all { overLimit(it) || coolingDown(it) } -> "All providers are rate-limited or over their daily limit right now."
                else -> "No configured model fits this request (context too long?)."
            }
            return Selection(emptyList(), why)
        }
        val wanted = when (task.complexity) {
            Complexity.SIMPLE -> CapabilityTier.ECONOMY
            Complexity.MODERATE -> CapabilityTier.STANDARD
            Complexity.COMPLEX -> CapabilityTier.ADVANCED
        }
        val ranked = candidates.sortedWith(
            compareBy<ProviderConfig> { kotlin.math.abs(it.tier.ordinal - wanted.ordinal) + (if (task.complexity == Complexity.COMPLEX && it.tier.ordinal < wanted.ordinal) 1 else 0) }
                .thenBy { if (task.needsTools && it.supportsTools) 0 else 1 }
                .thenBy { if (it.freeTier == dev.ciel.ai.FreeTier.FREE) 0 else 1 }
                .thenBy { if (it.id == preferredId) 0 else 1 }
                .thenBy { usage.requestsToday(it.id) }
        )
        val top = ranked.first()
        return Selection(ranked, "Auto: ${task.complexity.name.lowercase()} request → ${top.displayName} (${top.tier.name.lowercase()} tier)")
    }

    private fun overLimit(p: ProviderConfig) = p.dailyRequestLimit?.let { usage.requestsToday(p.id) >= it } ?: false
    private fun coolingDown(p: ProviderConfig) = usage.cooldownUntil(p.id) > clock()

    fun eligible(p: ProviderConfig, task: TaskProfile): Boolean {
        if (overLimit(p) || coolingDown(p)) return false
        if (task.needsVision && !p.supportsVision) return false
        if (p.contextWindow?.let { task.estimatedTokens > it } == true) return false
        return true
    }
}
