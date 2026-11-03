package dev.ciel.desktop.core

import dev.ciel.ai.ProviderConfig
import dev.ciel.ai.ProviderConfigCodec
import dev.ciel.core.json.Json
import dev.ciel.core.json.json
import dev.ciel.memory.MemoryCategory
import dev.ciel.memory.MemoryItem
import dev.ciel.memory.MemoryRepository
import dev.ciel.research.SearchProviderKind
import dev.ciel.windows.Autonomy
import dev.ciel.windows.GameProfile
import dev.ciel.windows.GamingPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Base64

/** %APPDATA%\Ciel on Windows (~/.ciel elsewhere, for development). */
object DesktopPaths {
    val dataDir: File = (System.getenv("APPDATA")?.let { File(it, "Ciel") } ?: File(System.getProperty("user.home"), ".ciel")).apply { mkdirs() }
}

data class DesktopSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val preferredProviderId: String? = null,
    val theme: String = "AZURE_CALM",
    val formOfAddress: String = "Master",
    val autonomy: Autonomy = Autonomy.ASSIST,
    val floatingAssistant: Boolean = true,
    val perfOverlay: Boolean = false,
    val searchKind: SearchProviderKind? = null,
    val gameProfiles: List<GameProfile> = listOf(GameProfile.MINECRAFT),
    val scanExclusions: List<String> = emptyList(),
    val speakReplies: Boolean = false,
)

/** Settings as one JSON file (no secrets in it: API keys live in [SecretStore]). */
class SettingsStore(private val file: File = File(DesktopPaths.dataDir, "settings.json")) {
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<DesktopSettings> = _flow.asStateFlow()
    val current: DesktopSettings get() = _flow.value

    @Synchronized fun update(t: (DesktopSettings) -> DesktopSettings) { _flow.value = t(_flow.value); save(_flow.value) }

    private fun save(s: DesktopSettings) = runCatching {
        file.writeText(json {
            "providers" to s.providers.map { ProviderConfigCodec.encode(it) }; opt("preferred", s.preferredProviderId)
            "theme" to s.theme; "address" to s.formOfAddress; "autonomy" to s.autonomy.name; "floating" to s.floatingAssistant
            "overlay" to s.perfOverlay; opt("search", s.searchKind?.name); "speak" to s.speakReplies; "exclusions" to s.scanExclusions
            "games" to s.gameProfiles.map { g -> json { "name" to g.name; "exe" to g.exeNames; "keep" to g.keep; "preset" to g.preset.name; "allow" to g.allowClose; "auto" to g.autoApprove; opt("marker", g.commandLineMarker) } }
        }.toString())
    }

    private fun load(): DesktopSettings = runCatching {
        val o = Json.parse(file.readText())
        DesktopSettings(
            providers = o["providers"]?.array.orEmpty().mapNotNull { ProviderConfigCodec.decode(it) },
            preferredProviderId = o["preferred"]?.string, theme = o["theme"]?.string ?: "AZURE_CALM", formOfAddress = o["address"]?.string ?: "Master",
            autonomy = runCatching { Autonomy.valueOf(o["autonomy"]?.string ?: "") }.getOrDefault(Autonomy.ASSIST),
            floatingAssistant = o["floating"]?.bool ?: true, perfOverlay = o["overlay"]?.bool ?: false,
            searchKind = o["search"]?.string?.let { runCatching { SearchProviderKind.valueOf(it) }.getOrNull() }, speakReplies = o["speak"]?.bool ?: false,
            scanExclusions = o["exclusions"]?.array.orEmpty().mapNotNull { it.string },
            gameProfiles = o["games"]?.array?.mapNotNull { g ->
                GameProfile(g["name"]?.string ?: return@mapNotNull null, g["exe"]?.array.orEmpty().mapNotNull { it.string }, g["keep"]?.array.orEmpty().mapNotNull { it.string },
                    runCatching { GamingPreset.valueOf(g["preset"]?.string ?: "") }.getOrDefault(GamingPreset.BALANCED), g["allow"]?.array.orEmpty().mapNotNull { it.string },
                    g["auto"]?.bool ?: false, g["marker"]?.string)
            } ?: listOf(GameProfile.MINECRAFT),
        )
    }.getOrDefault(DesktopSettings())
}

/** API keys, encrypted for this Windows user (DPAPI). Never in settings, logs or sync. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun delete(name: String)
    fun has(name: String) = get(name) != null
}

/** Stores secrets as `name → base64(protect(value))`; [protect]/[unprotect] are DPAPI on Windows. */
class FileSecretStore(private val file: File, private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray) : SecretStore {
    private fun read(): MutableMap<String, String> = runCatching {
        Json.parse(file.readText()).obj!!.fields.mapValues { it.value.string ?: "" }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    private fun write(m: Map<String, String>) { file.writeText(json { m.forEach { (k, v) -> k to v } }.toString()) }
    @Synchronized override fun get(name: String): String? = read()[name]?.let { runCatching { String(unprotect(Base64.getDecoder().decode(it)), Charsets.UTF_8) }.getOrNull() }
    @Synchronized override fun put(name: String, value: String) { write(read().apply { put(name, Base64.getEncoder().encodeToString(protect(value.toByteArray(Charsets.UTF_8)))) }) }
    @Synchronized override fun delete(name: String) { write(read().apply { remove(name) }) }
}

/** Memories in a JSON file (the same categories and controls as on the phone). */
class FileMemoryRepository(private val file: File = File(DesktopPaths.dataDir, "memory.json")) : MemoryRepository {
    private val state = MutableStateFlow(load())
    override fun observeAll(): Flow<List<MemoryItem>> = state.map { it.values.sortedByDescending { m -> m.updatedAt } }
    override suspend fun all() = state.value.values.toList()
    override suspend fun upsert(item: MemoryItem) { state.value = state.value + (item.id to item); save() }
    override suspend fun delete(id: String) { state.value = state.value - id; save() }
    override suspend fun deleteCategory(category: MemoryCategory) { state.value = state.value.filterValues { it.category != category }; save() }
    override suspend fun deleteAll() { state.value = emptyMap(); save() }

    private fun save() = runCatching {
        file.writeText(json { "items" to state.value.values.map { m -> json {
            "id" to m.id; "cat" to m.category.name; "content" to m.content; "tags" to m.tags; "source" to m.source; "pinned" to m.pinned
            "created" to m.createdAt; "updated" to m.updatedAt; opt("expires", m.expiresAt)
        } } }.toString())
    }
    private fun load(): Map<String, MemoryItem> = runCatching {
        Json.parse(file.readText())["items"]?.array.orEmpty().mapNotNull { o ->
            MemoryItem(o["id"]?.string ?: return@mapNotNull null, runCatching { MemoryCategory.valueOf(o["cat"]?.string ?: "") }.getOrDefault(MemoryCategory.PREFERENCE),
                o["content"]?.string ?: "", o["tags"]?.array.orEmpty().mapNotNull { it.string }, o["source"]?.string ?: "user", o["pinned"]?.bool ?: false,
                o["created"]?.long ?: 0, o["updated"]?.long ?: 0, o["expires"]?.long)
        }.associateBy { it.id }
    }.getOrDefault(emptyMap())
}
