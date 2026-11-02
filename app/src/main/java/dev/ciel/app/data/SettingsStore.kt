package dev.ciel.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ciel.ai.AuthStyle
import dev.ciel.ai.CapabilityTier
import dev.ciel.ai.FreeTier
import dev.ciel.ai.ProtocolKind
import dev.ciel.ai.ProviderConfig
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.JsonValue
import dev.ciel.core.json.json
import dev.ciel.engine.EngineSettings
import dev.ciel.engine.PersonaSettings
import dev.ciel.memory.MemoryCategory
import dev.ciel.memory.MemorySettings
import dev.ciel.research.SearchProviderKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

data class VoiceSettings(
    val voiceName: String? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val speakResponses: Boolean = true,
    val continuousConversation: Boolean = false,
)

/**
 * "Hey Ciel" and "Ciel". The offline English model has no word "ciel", so the closest-sounding
 * vocabulary ("seal") is accepted too.
 */
val DEFAULT_WAKE_PHRASES = listOf("hey ciel", "ciel", "hey seal", "seal", "hey see el")

data class AppSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val engine: EngineSettings = EngineSettings(),
    val memory: MemorySettings = MemorySettings(),
    val voice: VoiceSettings = VoiceSettings(),
    val searchKind: SearchProviderKind? = null,
    val searchBaseUrl: String? = null,
    val wakeWordEnabled: Boolean = false,
    val wakePhrases: List<String> = DEFAULT_WAKE_PHRASES,
    val wakeSensitivity: dev.ciel.app.voice.WakeSensitivity = dev.ciel.app.voice.WakeSensitivity.NORMAL,
    /** Also wake on "Ciel" alone (not just "Hey Ciel"). */
    val wakeSingleWord: Boolean = true,
    val floatingEnabled: Boolean = false,
    /** Lets autonomous automation send screenshots to a vision model (in addition to the element tree). */
    val allowScreenshots: Boolean = false,
    /** Read text on game screens on the phone (ML Kit, offline) during game tasks. */
    val gameScreenReading: Boolean = true,
    val reducedMotion: Boolean = false,
    val discordWebhooks: List<String> = emptyList(),
    val appearance: dev.ciel.app.design.Appearance = dev.ciel.app.design.Appearance(),
    /** First-run setup (design → connect → Hey Ciel) completed or skipped. */
    val onboardingDone: Boolean = false,
    /** Shows URLs/models/headers editing for providers. Off for normal use. */
    val developerMode: Boolean = false,
    val tasks: dev.ciel.app.TaskSettings = dev.ciel.app.TaskSettings(),
)

private val Context.dataStore by preferencesDataStore("ciel_settings")
private val KEY = stringPreferencesKey("settings_json_v1")

/** All settings as one JSON document in DataStore. Secrets are NOT stored here (see KeyVault). */
class SettingsStore(private val context: Context, scope: CoroutineScope) {
    val flow: StateFlow<AppSettings> = context.dataStore.data
        .map { prefs -> prefs[KEY]?.let { decode(it) } ?: AppSettings() }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { load() })

    private suspend fun load(): AppSettings = context.dataStore.data.first()[KEY]?.let { decode(it) } ?: AppSettings()

    val current: AppSettings get() = flow.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val now = prefs[KEY]?.let { decode(it) } ?: AppSettings()
            prefs[KEY] = encode(transform(now))
        }
    }

    companion object {
        fun encode(s: AppSettings): String = json {
            "providers" to s.providers.map { encodeProvider(it) }
            "engine" to json {
                "formOfAddress" to s.engine.persona.formOfAddress
                "useSystemMarkers" to s.engine.persona.useSystemMarkers
                opt("userName", s.engine.persona.userName)
                "language" to s.engine.persona.language
                "autoSelect" to s.engine.autoSelect
                opt("preferredProviderId", s.engine.preferredProviderId)
                "streaming" to s.engine.streaming
                "shareMemoryWithProviders" to s.engine.shareMemoryWithProviders
                "maxToolRounds" to s.engine.maxToolRounds
            }
            "memory" to json {
                "enabled" to s.memory.enabled
                "retained" to s.memory.retainedCategories.map { it.name }
                "allowInferred" to s.memory.allowInferredMemories
                "taskTtlHours" to s.memory.taskMemoryTtlHours
            }
            "voice" to json {
                opt("voiceName", s.voice.voiceName)
                "speechRate" to s.voice.speechRate
                "pitch" to s.voice.pitch
                "speakResponses" to s.voice.speakResponses
                "continuous" to s.voice.continuousConversation
            }
            opt("searchKind", s.searchKind?.name)
            opt("searchBaseUrl", s.searchBaseUrl)
            "wakeWordEnabled" to s.wakeWordEnabled
            "wakePhrases" to s.wakePhrases
            "wakeSensitivity" to s.wakeSensitivity.name
            "wakeSingleWord" to s.wakeSingleWord
            "floatingEnabled" to s.floatingEnabled
            "allowScreenshots" to s.allowScreenshots
            "gameScreenReading" to s.gameScreenReading
            "reducedMotion" to s.reducedMotion
            "discordWebhooks" to s.discordWebhooks
            "appearance" to json {
                "design" to s.appearance.design.name
                "theme" to s.appearance.theme.name
                opt("accent", s.appearance.accent?.name)
                "animation" to s.appearance.animation.name
                "themeMode" to s.appearance.themeMode.name
                "glow" to s.appearance.glow
                "particles" to s.appearance.particles
                "transparency" to s.appearance.transparency
                "ringSpeed" to s.appearance.ringSpeed
                "floatingSize" to s.appearance.floatingSize.name
            }
            "onboardingDone" to s.onboardingDone
            "developerMode" to s.developerMode
            "tasks" to json {
                "maxSteps" to s.tasks.maxSteps
                "timeoutMinutes" to s.tasks.timeoutMinutes
                "notifyOnFinish" to s.tasks.notifyOnFinish
                "templates" to s.tasks.templates.map { dev.ciel.app.TaskSpecCodec.encode(it) }
            }
        }.toString()

        fun decode(text: String): AppSettings {
            val o = Json.parseOrNull(text)?.obj ?: return AppSettings()
            val d = AppSettings()
            val e = o["engine"]
            val m = o["memory"]
            val v = o["voice"]
            return AppSettings(
                providers = o["providers"]?.array?.mapNotNull { decodeProvider(it) } ?: emptyList(),
                engine = EngineSettings(
                    persona = PersonaSettings(
                        formOfAddress = e?.get("formOfAddress")?.string ?: d.engine.persona.formOfAddress,
                        useSystemMarkers = e?.get("useSystemMarkers")?.bool ?: true,
                        userName = e?.get("userName")?.string,
                        language = e?.get("language")?.string ?: "English",
                    ),
                    autoSelect = e?.get("autoSelect")?.bool ?: true,
                    preferredProviderId = e?.get("preferredProviderId")?.string,
                    streaming = e?.get("streaming")?.bool ?: true,
                    shareMemoryWithProviders = e?.get("shareMemoryWithProviders")?.bool ?: true,
                    maxToolRounds = e?.get("maxToolRounds")?.int ?: 6,
                ),
                memory = MemorySettings(
                    enabled = m?.get("enabled")?.bool ?: true,
                    retainedCategories = m?.get("retained")?.array?.mapNotNull { runCatching { MemoryCategory.valueOf(it.string ?: "") }.getOrNull() }?.toSet()
                        ?: MemoryCategory.values().toSet(),
                    allowInferredMemories = m?.get("allowInferred")?.bool ?: false,
                    taskMemoryTtlHours = m?.get("taskTtlHours")?.int ?: 24,
                ),
                voice = VoiceSettings(
                    voiceName = v?.get("voiceName")?.string,
                    speechRate = v?.get("speechRate")?.number?.toFloat() ?: 1f,
                    pitch = v?.get("pitch")?.number?.toFloat() ?: 1f,
                    speakResponses = v?.get("speakResponses")?.bool ?: true,
                    continuousConversation = v?.get("continuous")?.bool ?: false,
                ),
                searchKind = o["searchKind"]?.string?.let { runCatching { SearchProviderKind.valueOf(it) }.getOrNull() },
                searchBaseUrl = o["searchBaseUrl"]?.string,
                wakeWordEnabled = o["wakeWordEnabled"]?.bool ?: false,
                wakePhrases = o["wakePhrases"]?.array?.mapNotNull { it.string }?.ifEmpty { null }?.takeIf { it != listOf("ciel", "seal") } ?: d.wakePhrases,
                floatingEnabled = o["floatingEnabled"]?.bool ?: false,
                wakeSensitivity = runCatching { dev.ciel.app.voice.WakeSensitivity.valueOf(o["wakeSensitivity"]?.string ?: "") }.getOrDefault(dev.ciel.app.voice.WakeSensitivity.NORMAL),
                wakeSingleWord = o["wakeSingleWord"]?.bool ?: true,
                allowScreenshots = o["allowScreenshots"]?.bool ?: false,
                gameScreenReading = o["gameScreenReading"]?.bool ?: true,
                reducedMotion = o["reducedMotion"]?.bool ?: false,
                discordWebhooks = o["discordWebhooks"]?.array?.mapNotNull { it.string } ?: emptyList(),
                appearance = o["appearance"]?.let { a ->
                    dev.ciel.app.design.Appearance(
                        design = runCatching { dev.ciel.app.design.CielDesign.valueOf(a["design"]?.string ?: "") }.getOrDefault(dev.ciel.app.design.CielDesign.HYBRID),
                        accent = a["accent"]?.string?.let { n -> runCatching { dev.ciel.app.design.AccentColor.valueOf(n) }.getOrNull() },
                        animation = runCatching { dev.ciel.app.design.AnimationLevel.valueOf(a["animation"]?.string ?: "") }.getOrDefault(dev.ciel.app.design.AnimationLevel.FULL),
                        themeMode = runCatching { dev.ciel.app.design.ThemeMode.valueOf(a["themeMode"]?.string ?: "") }.getOrDefault(dev.ciel.app.design.ThemeMode.SYSTEM),
                        theme = runCatching { dev.ciel.app.design.ThemeStyle.valueOf(a["theme"]?.string ?: "") }.getOrDefault(dev.ciel.app.design.ThemeStyle.AZURE_CALM),
                        glow = a["glow"]?.number?.toFloat()?.coerceIn(0f, 1f) ?: 0.7f,
                        particles = a["particles"]?.number?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f,
                        transparency = a["transparency"]?.number?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f,
                        ringSpeed = a["ringSpeed"]?.number?.toFloat()?.coerceIn(0.25f, 2f) ?: 1f,
                        floatingSize = runCatching { dev.ciel.app.design.FloatingSize.valueOf(a["floatingSize"]?.string ?: "") }.getOrDefault(dev.ciel.app.design.FloatingSize.MEDIUM),
                    )
                } ?: dev.ciel.app.design.Appearance(),
                // Existing installs with a provider already configured skip the first-run flow.
                onboardingDone = o["onboardingDone"]?.bool ?: (o["providers"]?.array?.isNotEmpty() == true),
                developerMode = o["developerMode"]?.bool ?: false,
                tasks = o["tasks"]?.let { t ->
                    dev.ciel.app.TaskSettings(
                        maxSteps = t["maxSteps"]?.int?.coerceIn(5, 150) ?: 40,
                        timeoutMinutes = t["timeoutMinutes"]?.int?.coerceIn(1, 120) ?: 10,
                        notifyOnFinish = t["notifyOnFinish"]?.bool ?: true,
                        templates = t["templates"]?.array?.mapNotNull { dev.ciel.app.TaskSpecCodec.decode(it.toString()) } ?: emptyList(),
                    )
                } ?: dev.ciel.app.TaskSettings(),
            )
        }

        fun encodeProvider(p: ProviderConfig): JsonObject = json {
            "id" to p.id; "displayName" to p.displayName; "protocol" to p.protocol.name; "baseUrl" to p.baseUrl; "model" to p.model
            "authStyle" to p.authStyle.name; opt("authHeaderName", p.authHeaderName); "extraHeaders" to p.extraHeaders
            opt("contextWindow", p.contextWindow); "supportsVision" to p.supportsVision; "supportsTools" to p.supportsTools
            "supportsStreaming" to p.supportsStreaming; "tier" to p.tier.name; "freeTier" to p.freeTier.name
            opt("rateLimitNote", p.rateLimitNote); opt("dailyRequestLimit", p.dailyRequestLimit)
            opt("temperature", p.temperature); opt("maxOutputTokens", p.maxOutputTokens)
            "enabled" to p.enabled; "dataPolicyAcknowledged" to p.dataPolicyAcknowledged
        }

        fun decodeProvider(v: JsonValue): ProviderConfig? {
            val id = v["id"]?.string ?: return null
            return ProviderConfig(
                id = id,
                displayName = v["displayName"]?.string ?: id,
                protocol = runCatching { ProtocolKind.valueOf(v["protocol"]?.string ?: "") }.getOrDefault(ProtocolKind.OPENAI_CHAT_COMPLETIONS),
                baseUrl = v["baseUrl"]?.string ?: return null,
                model = v["model"]?.string ?: return null,
                authStyle = runCatching { AuthStyle.valueOf(v["authStyle"]?.string ?: "") }.getOrDefault(AuthStyle.BEARER),
                authHeaderName = v["authHeaderName"]?.string,
                extraHeaders = v["extraHeaders"]?.obj?.fields?.mapValues { it.value.string ?: "" } ?: emptyMap(),
                contextWindow = v["contextWindow"]?.int,
                supportsVision = v["supportsVision"]?.bool ?: false,
                supportsTools = v["supportsTools"]?.bool ?: false,
                supportsStreaming = v["supportsStreaming"]?.bool ?: true,
                tier = runCatching { CapabilityTier.valueOf(v["tier"]?.string ?: "") }.getOrDefault(CapabilityTier.STANDARD),
                freeTier = runCatching { FreeTier.valueOf(v["freeTier"]?.string ?: "") }.getOrDefault(FreeTier.UNKNOWN),
                rateLimitNote = v["rateLimitNote"]?.string,
                dailyRequestLimit = v["dailyRequestLimit"]?.int,
                temperature = v["temperature"]?.number,
                maxOutputTokens = v["maxOutputTokens"]?.int,
                enabled = v["enabled"]?.bool ?: true,
                dataPolicyAcknowledged = v["dataPolicyAcknowledged"]?.bool ?: false,
            )
        }
    }
}
