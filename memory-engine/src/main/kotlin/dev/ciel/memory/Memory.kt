package dev.ciel.memory

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.JsonObject
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.reqStr
import dev.ciel.skills.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** The six memory categories required by the spec. */
enum class MemoryCategory(val label: String) {
    CONVERSATION("Short-term conversation"),
    PREFERENCE("Long-term preferences"),
    PROJECT("Projects"),
    APPLICATION("Applications"),
    GAME("Game profiles"),
    TASK("Temporary task memory"),
}

data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    val content: String,
    val tags: List<String> = emptyList(),
    /** "user" (explicitly told), "ciel" (inferred by Ciel), "task" (recorded during a task). */
    val source: String = "user",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    /** For TASK memories: auto-expire. */
    val expiresAt: Long? = null,
)

/** Storage abstraction; Room on Android, in-memory in tests. */
interface MemoryRepository {
    fun observeAll(): Flow<List<MemoryItem>>
    suspend fun all(): List<MemoryItem>
    suspend fun upsert(item: MemoryItem)
    suspend fun delete(id: String)
    suspend fun deleteCategory(category: MemoryCategory)
    suspend fun deleteAll()
}

class InMemoryMemoryRepository : MemoryRepository {
    private val state = MutableStateFlow<Map<String, MemoryItem>>(emptyMap())
    override fun observeAll(): Flow<List<MemoryItem>> = state.map { it.values.sortedByDescending { m -> m.updatedAt } }
    override suspend fun all() = state.value.values.toList()
    override suspend fun upsert(item: MemoryItem) { state.value = state.value + (item.id to item) }
    override suspend fun delete(id: String) { state.value = state.value - id }
    override suspend fun deleteCategory(category: MemoryCategory) { state.value = state.value.filterValues { it.category != category } }
    override suspend fun deleteAll() { state.value = emptyMap() }
}

/** User-controlled retention settings. */
data class MemorySettings(
    val enabled: Boolean = true,
    val retainedCategories: Set<MemoryCategory> = MemoryCategory.values().toSet(),
    /** Ciel may save things it infers (not only explicit "remember X"). */
    val allowInferredMemories: Boolean = false,
    val taskMemoryTtlHours: Int = 24,
)

/**
 * Detects content that should never be stored: passwords, card numbers, one-time codes,
 * API keys, ID numbers. Requirement: do not store sensitive information unnecessarily.
 */
object SensitiveDataFilter {
    private val rules = listOf(
        Regex("(?i)\\b(password|passcode|pwd|pin code|secret answer)\\b\\s*(is|:|=)") to "a password",
        Regex("\\b(?:\\d[ -]?){13,19}\\b") to "a card or account number",
        Regex("(?i)\\b(otp|one[- ]time (pass)?code|verification code)\\b") to "a one-time code",
        Regex("\\b(sk-|gsk_|AIza)[A-Za-z0-9_\\-]{10,}") to "an API key",
        Regex("(?i)\\bcvv\\b|\\bcvc\\b") to "card security data",
        Regex("\\b\\d{13}\\b") to "an ID number",
    )

    /** Returns a reason if [text] looks sensitive, else null. */
    fun check(text: String): String? = rules.firstOrNull { it.first.containsMatchIn(text) }?.second
}

/**
 * Memory manager: policy, search and the context Ciel sees. Retrieved memories are passed
 * to the model as background data, never as instructions.
 */
class MemoryManager(
    private val repo: MemoryRepository,
    initialSettings: MemorySettings = MemorySettings(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<MemorySettings> = _settings.asStateFlow()
    fun updateSettings(s: MemorySettings) { _settings.value = s }

    fun observe(): Flow<List<MemoryItem>> = repo.observeAll()

    sealed class SaveResult {
        data class Saved(val item: MemoryItem) : SaveResult()
        data class Rejected(val reason: String) : SaveResult()
    }

    suspend fun remember(content: String, category: MemoryCategory, tags: List<String> = emptyList(), source: String = "user"): SaveResult {
        val s = _settings.value
        if (!s.enabled) return SaveResult.Rejected("Memory is disabled in settings.")
        if (category !in s.retainedCategories) return SaveResult.Rejected("${category.label} memories are turned off in settings.")
        if (source == "ciel" && !s.allowInferredMemories) return SaveResult.Rejected("Saving inferred memories is turned off.")
        SensitiveDataFilter.check(content)?.let { return SaveResult.Rejected("Not stored: it appears to contain $it.") }
        val trimmed = content.trim().take(2000)
        // De-duplicate near-identical memories by updating instead of inserting.
        val existing = repo.all().firstOrNull { it.category == category && similarity(it.content, trimmed) >= 0.75 }
        val item = existing?.copy(content = trimmed, tags = (existing.tags + tags).distinct(), updatedAt = clock())
            ?: MemoryItem(category = category, content = trimmed, tags = tags, source = source, createdAt = clock(), updatedAt = clock(),
                expiresAt = if (category == MemoryCategory.TASK) clock() + s.taskMemoryTtlHours * 3_600_000L else null)
        repo.upsert(item)
        return SaveResult.Saved(item)
    }

    suspend fun edit(id: String, content: String): SaveResult {
        SensitiveDataFilter.check(content)?.let { return SaveResult.Rejected("Not stored: it appears to contain $it.") }
        val item = repo.all().firstOrNull { it.id == id } ?: return SaveResult.Rejected("Memory not found.")
        val updated = item.copy(content = content.trim(), updatedAt = clock())
        repo.upsert(updated)
        return SaveResult.Saved(updated)
    }

    suspend fun forget(id: String) = repo.delete(id)
    suspend fun forgetCategory(c: MemoryCategory) = repo.deleteCategory(c)
    suspend fun forgetEverything() = repo.deleteAll()

    suspend fun purgeExpired() {
        val now = clock()
        repo.all().filter { it.expiresAt != null && it.expiresAt < now }.forEach { repo.delete(it.id) }
    }

    /** Keyword + recency ranking. Works fully offline. */
    suspend fun search(query: String, limit: Int = 8, category: MemoryCategory? = null): List<MemoryItem> {
        if (!_settings.value.enabled) return emptyList()
        val q = tokens(query)
        val now = clock()
        return repo.all()
            .filter { (category == null || it.category == category) && (it.expiresAt == null || it.expiresAt > now) }
            .map { item ->
                val t = tokens(item.content + " " + item.tags.joinToString(" "))
                val overlap = if (q.isEmpty()) 0.0 else q.count { it in t }.toDouble() / q.size
                val ageDays = (now - item.updatedAt) / 86_400_000.0
                val recency = 1.0 / (1.0 + ageDays / 30.0)
                item to (overlap * 3 + recency * 0.5 + (if (item.pinned) 1.0 else 0.0) + (if (item.category == MemoryCategory.PREFERENCE) 0.3 else 0.0))
            }
            .filter { q.isEmpty() || it.second > 0.6 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Memories to include with a conversation turn. */
    suspend fun contextFor(userMessage: String): String? {
        if (!_settings.value.enabled) return null
        val prefs = repo.all().filter { it.category == MemoryCategory.PREFERENCE || it.pinned }.sortedByDescending { it.updatedAt }.take(10)
        val relevant = search(userMessage, 6).filter { it !in prefs }
        val all = prefs + relevant
        if (all.isEmpty()) return null
        return all.joinToString("\n") { "- [${it.category.label}] ${it.content}" }
    }

    companion object {
        private val stop = setOf("the", "a", "an", "is", "are", "my", "me", "i", "to", "of", "and", "in", "on", "for", "what", "do", "you", "it", "that", "this", "with", "ciel")
        fun tokens(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }.map { it.removeSuffix("s") }.toSet()
        fun similarity(a: String, b: String): Double {
            val ta = tokens(a); val tb = tokens(b)
            if (ta.isEmpty() || tb.isEmpty()) return 0.0
            return (ta intersect tb).size.toDouble() / (ta union tb).size
        }
    }
}

/** The Memory skill exposes remember/recall/forget to Ciel. */
class MemorySkill(private val memory: MemoryManager) : Skill {
    override val id = "memory"
    override val displayName = "Memory"
    override val description = "Remember, recall and forget information about the user."
    override val worksOffline = true
    override val tools = listOf(
        ToolSpec("remember", "Store a fact the user asked you to remember, or a clearly useful lasting preference.",
            Schema.obj(listOf("content", "category"),
                "content" to Schema.str("The fact, phrased as a standalone statement"),
                "category" to Schema.str("Category", MemoryCategory.values().map { it.name.lowercase() }),
                "inferred" to Schema.bool("True if the user did not explicitly ask you to remember this"))),
        ToolSpec("recall", "Search stored memories.", Schema.obj(listOf("query"), "query" to Schema.str("What to look for"))),
        ToolSpec("forget", "Delete memories matching a description. Ask the user before deleting more than one.",
            Schema.obj(listOf("query"), "query" to Schema.str("Which memory to delete"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome = when (tool) {
        "remember" -> {
            val cat = runCatching { MemoryCategory.valueOf(args.reqStr("category").uppercase()) }.getOrDefault(MemoryCategory.PREFERENCE)
            when (val r = memory.remember(args.reqStr("content"), cat, source = if (args["inferred"]?.bool == true) "ciel" else "user")) {
                is MemoryManager.SaveResult.Saved -> ToolOutcome.ok("Stored in ${cat.label}.", verified = true)
                is MemoryManager.SaveResult.Rejected -> ToolOutcome.fail(r.reason)
            }
        }
        "recall" -> {
            val found = memory.search(args.reqStr("query"))
            ToolOutcome.ok(if (found.isEmpty()) "No matching memories." else found.joinToString("\n") { "${it.id.take(8)} [${it.category.label}] ${it.content}" }, true)
        }
        "forget" -> {
            val matches = memory.search(args.reqStr("query"), 5)
            when {
                matches.isEmpty() -> ToolOutcome.fail("No matching memory found.")
                matches.size == 1 || args.str("query")?.let { q -> matches.first().content.contains(q, true) } == true -> {
                    val target = matches.first()
                    val ok = ctx.confirm.confirm(dev.ciel.task.ConfirmationRequest("Delete memory?", target.content, dev.ciel.task.Risk.CONSEQUENTIAL, "Delete memory"))
                    if (ok) { memory.forget(target.id); ToolOutcome.ok("Deleted: ${target.content}", true) } else ToolOutcome.fail("The user kept the memory.")
                }
                else -> ToolOutcome.fail("Several memories match; ask the user which one:\n" + matches.joinToString("\n") { "- ${it.content}" })
            }
        }
        else -> ToolOutcome.fail("Unknown tool $tool")
    }
}

/** Export / import of memories as JSON (Memory → Export / Import). */
object MemoryBackup {
    fun export(items: List<MemoryItem>): String = dev.ciel.core.json.json {
        "format" to "ciel-memory-v1"
        "exportedAt" to System.currentTimeMillis()
        "memories" to items.map { m ->
            dev.ciel.core.json.json {
                "category" to m.category.name; "content" to m.content; "tags" to m.tags
                "source" to m.source; "pinned" to m.pinned; "createdAt" to m.createdAt
            }
        }
    }.toString()

    data class ImportResult(val imported: Int, val skipped: Int, val reasons: List<String>)

    /** Imports through [MemoryManager.remember], so sensitive-data and retention rules still apply. */
    suspend fun import(text: String, into: MemoryManager): ImportResult {
        val root = dev.ciel.core.json.Json.parseOrNull(text)
        if (root?.get("format")?.string != "ciel-memory-v1") return ImportResult(0, 0, listOf("This isn't a Ciel memory backup."))
        var ok = 0; var skipped = 0; val reasons = mutableListOf<String>()
        root["memories"]?.array.orEmpty().forEach { m ->
            val content = m["content"]?.string ?: return@forEach
            val cat = runCatching { MemoryCategory.valueOf(m["category"]?.string ?: "") }.getOrDefault(MemoryCategory.PREFERENCE)
            val tags = m["tags"]?.array?.mapNotNull { it.string } ?: emptyList()
            when (val r = into.remember(content, cat, tags, source = "user")) {
                is MemoryManager.SaveResult.Saved -> ok++
                is MemoryManager.SaveResult.Rejected -> { skipped++; if (reasons.size < 5) reasons += r.reason }
            }
        }
        return ImportResult(ok, skipped, reasons)
    }
}
