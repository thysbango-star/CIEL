package dev.ciel.game

import dev.ciel.core.json.JsonValue
import dev.ciel.core.json.json

/** How sure Ciel is about something she learned. */
enum class Confidence {
    LOW, MEDIUM, HIGH;

    fun up() = values()[minOf(ordinal + 1, values().size - 1)]
    fun down() = values()[maxOf(ordinal - 1, 0)]
}

/** Where a piece of knowledge came from. Ciel never invents game facts: everything has a source. */
enum class KnowledgeSource { OBSERVED, USER, IMPORTED }

enum class KnowledgeKind {
    /** How to reach a screen or system ("Quests: tap the 'Quests' button on the kingdom screen"). */
    LOCATION,
    /** What can be done somewhere ("Claim completed quest rewards"). */
    ACTION,
    /** How the game works ("Production buildings queue items; collection needs a tap"). */
    MECHANIC,
    /** A cost, danger or restriction ("Upgrade spends coins"). */
    RISK,
    /** A sequence that achieved an objective before. */
    WORKFLOW,
    /** Something seen but not understood yet. */
    UNRESOLVED,
}

/**
 * One learned fact. Locations are stored semantically (labels and landmarks), never as trusted
 * coordinates: a position, if known, is only a hint that must be re-validated on screen.
 */
data class KnowledgeEntry(
    val system: String,
    val kind: KnowledgeKind,
    val title: String,
    val detail: String,
    /** Semantic steps from a known screen ("On Kingdom: tap 'Quests'"). */
    val howToReach: List<String> = emptyList(),
    /** Text that identifies the place ("Daily", "Weekly", "Claim all"). */
    val landmarks: List<String> = emptyList(),
    /** Last seen position as screen fractions — a hint only, never trusted blindly. */
    val positionHint: Pair<Float, Float>? = null,
    val confidence: Confidence = Confidence.LOW,
    val source: KnowledgeSource = KnowledgeSource.OBSERVED,
    val gameVersion: String? = null,
    val seenCount: Int = 1,
    val failCount: Int = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastVerified: Long = System.currentTimeMillis(),
    /** Set when the game updated after this was learned. */
    val needsRevalidation: Boolean = false,
) {
    val key: String get() = (system.trim() + "|" + kind.name + "|" + title.trim()).lowercase()
}

/**
 * Searchable game knowledge. Immutable: every operation returns a new base. Merging the same fact
 * again raises confidence (it was observed again); contradictions lower it; a game update marks
 * everything for re-validation and lowers confidence one level.
 */
data class GameKnowledgeBase(val entries: List<KnowledgeEntry> = emptyList()) {

    fun record(entry: KnowledgeEntry, now: Long = System.currentTimeMillis()): GameKnowledgeBase {
        val clean = SensitiveFilter.clean(entry) ?: return this
        val existing = entries.firstOrNull { it.key == clean.key }
        val merged = if (existing == null) clean else existing.copy(
            detail = if (clean.detail.length >= existing.detail.length) clean.detail else existing.detail,
            howToReach = clean.howToReach.ifEmpty { existing.howToReach },
            landmarks = (existing.landmarks + clean.landmarks).distinct().take(12),
            positionHint = clean.positionHint ?: existing.positionHint,
            // Seeing the same thing again is evidence; a user statement is trusted as given.
            confidence = maxOf(clean.confidence, if (clean.source == KnowledgeSource.OBSERVED) existing.confidence.up() else existing.confidence),
            seenCount = existing.seenCount + 1,
            gameVersion = clean.gameVersion ?: existing.gameVersion,
            lastVerified = now,
            needsRevalidation = false,
        )
        return GameKnowledgeBase((entries.filter { it.key != clean.key } + merged).takeLast(MAX_ENTRIES))
    }

    /** The fact did not hold this time (button missing, different screen): trust it less. */
    fun contradict(key: String): GameKnowledgeBase = GameKnowledgeBase(entries.map {
        if (it.key == key) it.copy(confidence = it.confidence.down(), failCount = it.failCount + 1, needsRevalidation = true) else it
    })

    /** Called when the installed game version differs from the one knowledge was learned on. */
    fun onGameUpdated(newVersion: String?): GameKnowledgeBase = GameKnowledgeBase(entries.map {
        if (it.gameVersion != null && it.gameVersion != newVersion && !it.needsRevalidation)
            it.copy(confidence = it.confidence.down(), needsRevalidation = true) else it
    })

    fun remove(key: String) = GameKnowledgeBase(entries.filter { it.key != key })

    /** Ranks entries by word overlap with [query] (systems, titles, details, landmarks). */
    fun search(query: String, limit: Int = 10): List<KnowledgeEntry> {
        val words = tokens(query)
        if (words.isEmpty()) return entries.sortedByDescending { it.lastVerified }.take(limit)
        return entries.map { e ->
            val hay = tokens(e.system + " " + e.title + " " + e.detail + " " + e.landmarks.joinToString(" ") + " " + e.howToReach.joinToString(" "))
            val matches = words.count { w -> hay.any { it == w || (w.length >= 4 && (it.startsWith(w) || w.startsWith(it))) } }
            Triple(e, matches, matches * 10 + e.confidence.ordinal * 2 - (if (e.needsRevalidation) 3 else 0))
        }.filter { it.second > 0 }.sortedByDescending { it.third }.take(limit).map { it.first }
    }

    val systems: List<String> get() = entries.map { it.system }.distinct().sorted()

    /** Compact, honest briefing for the planner: confidence and staleness are always stated. */
    fun briefing(objective: String, limit: Int = 14): String {
        val relevant = (search(objective, limit) + entries.filter { it.kind == KnowledgeKind.RISK }.take(4)).distinctBy { it.key }.take(limit)
        if (relevant.isEmpty()) return "No learned knowledge yet for this objective: explore carefully and read the screen."
        return relevant.joinToString("\n") { e ->
            val flags = listOfNotNull(e.confidence.name.lowercase() + " confidence",
                "needs re-checking (game updated)".takeIf { e.needsRevalidation }, "failed ${e.failCount}x".takeIf { e.failCount > 0 }).joinToString(", ")
            "- [${e.system}] ${e.title}: ${e.detail}" +
                (if (e.howToReach.isNotEmpty()) " Route: ${e.howToReach.joinToString(" → ")}." else "") +
                (if (e.landmarks.isNotEmpty()) " Look for: ${e.landmarks.take(5).joinToString { "\"$it\"" }}." else "") +
                " ($flags)"
        }
    }

    companion object {
        const val MAX_ENTRIES = 300
        private val stop = setOf("the", "a", "an", "my", "and", "or", "to", "of", "in", "on", "all", "ciel", "please", "for", "what", "with", "is", "are", "do", "can", "you")
        fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }.map { it.removeSuffix("s") }.toSet()

        fun encode(e: KnowledgeEntry) = json {
            "system" to e.system; "kind" to e.kind.name; "title" to e.title; "detail" to e.detail
            "howToReach" to e.howToReach; "landmarks" to e.landmarks
            e.positionHint?.let { "positionHint" to listOf(it.first.toDouble(), it.second.toDouble()) }
            "confidence" to e.confidence.name; "source" to e.source.name; opt("gameVersion", e.gameVersion)
            "seenCount" to e.seenCount; "failCount" to e.failCount; "firstSeen" to e.firstSeen; "lastVerified" to e.lastVerified
            "needsRevalidation" to e.needsRevalidation
        }

        fun decode(o: JsonValue, imported: Boolean = false): KnowledgeEntry? = runCatching {
            KnowledgeEntry(
                system = o["system"]?.string ?: return null, kind = KnowledgeKind.valueOf(o["kind"]?.string ?: "UNRESOLVED"),
                title = o["title"]?.string ?: return null, detail = o["detail"]?.string ?: "",
                howToReach = o["howToReach"]?.array?.mapNotNull { it.string } ?: emptyList(),
                landmarks = o["landmarks"]?.array?.mapNotNull { it.string } ?: emptyList(),
                positionHint = o["positionHint"]?.array?.let { a -> val x = a.getOrNull(0)?.number; val y = a.getOrNull(1)?.number; if (x != null && y != null) x.toFloat() to y.toFloat() else null },
                // Shared knowledge is never trusted more than "medium" until Ciel sees it herself.
                confidence = Confidence.valueOf(o["confidence"]?.string ?: "LOW").let { if (imported) minOf(it, Confidence.MEDIUM) else it },
                source = if (imported) KnowledgeSource.IMPORTED else KnowledgeSource.valueOf(o["source"]?.string ?: "OBSERVED"),
                gameVersion = o["gameVersion"]?.string,
                seenCount = o["seenCount"]?.int ?: 1, failCount = o["failCount"]?.int ?: 0,
                firstSeen = o["firstSeen"]?.long ?: 0, lastVerified = o["lastVerified"]?.long ?: 0,
                needsRevalidation = imported || (o["needsRevalidation"]?.bool ?: false),
            ).let { SensitiveFilter.clean(it) }
        }.getOrNull()
    }
}

/** Keeps account secrets out of game knowledge (passwords, tokens, e-mail, codes). */
object SensitiveFilter {
    private val secret = Regex("(?i)(password|passwd|pin code|token|session|auth|otp|verification code|e-?mail|@[a-z0-9-]+\\.[a-z]{2,}|\\b[A-Za-z0-9_-]{28,}\\b)")
    fun isSensitive(text: String) = secret.containsMatchIn(text)
    fun clean(e: KnowledgeEntry): KnowledgeEntry? {
        if (isSensitive(e.title) || isSensitive(e.system)) return null
        return e.copy(
            detail = if (isSensitive(e.detail)) "(details withheld: looked like account information)" else e.detail.take(400),
            landmarks = e.landmarks.filterNot { isSensitive(it) }.map { it.take(40) },
            howToReach = e.howToReach.filterNot { isSensitive(it) }.map { it.take(120) },
        )
    }
}
