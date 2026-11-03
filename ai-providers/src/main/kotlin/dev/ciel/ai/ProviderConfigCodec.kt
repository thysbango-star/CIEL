package dev.ciel.ai

import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.JsonValue
import dev.ciel.core.json.json

/** The same provider-settings format on every Ciel (phone and PC). API keys are never part of it. */
object ProviderConfigCodec {
    fun encode(p: ProviderConfig): JsonObject = json {
        "id" to p.id; "displayName" to p.displayName; "protocol" to p.protocol.name; "baseUrl" to p.baseUrl; "model" to p.model
        "authStyle" to p.authStyle.name; opt("authHeaderName", p.authHeaderName); "extraHeaders" to p.extraHeaders
        opt("contextWindow", p.contextWindow); "supportsVision" to p.supportsVision; "supportsTools" to p.supportsTools
        "supportsStreaming" to p.supportsStreaming; "tier" to p.tier.name; "freeTier" to p.freeTier.name
        opt("rateLimitNote", p.rateLimitNote); opt("dailyRequestLimit", p.dailyRequestLimit)
        opt("temperature", p.temperature); opt("maxOutputTokens", p.maxOutputTokens)
        "enabled" to p.enabled; "dataPolicyAcknowledged" to p.dataPolicyAcknowledged
    }

    fun decode(v: JsonValue): ProviderConfig? {
        val id = v["id"]?.string ?: return null
        return ProviderConfig(
            id = id, displayName = v["displayName"]?.string ?: id,
            protocol = runCatching { ProtocolKind.valueOf(v["protocol"]?.string ?: "") }.getOrDefault(ProtocolKind.OPENAI_CHAT_COMPLETIONS),
            baseUrl = v["baseUrl"]?.string ?: return null, model = v["model"]?.string ?: return null,
            authStyle = runCatching { AuthStyle.valueOf(v["authStyle"]?.string ?: "") }.getOrDefault(AuthStyle.BEARER),
            authHeaderName = v["authHeaderName"]?.string,
            extraHeaders = v["extraHeaders"]?.obj?.fields?.mapValues { it.value.string ?: "" } ?: emptyMap(),
            contextWindow = v["contextWindow"]?.int, supportsVision = v["supportsVision"]?.bool ?: false,
            supportsTools = v["supportsTools"]?.bool ?: false, supportsStreaming = v["supportsStreaming"]?.bool ?: true,
            tier = runCatching { CapabilityTier.valueOf(v["tier"]?.string ?: "") }.getOrDefault(CapabilityTier.STANDARD),
            freeTier = runCatching { FreeTier.valueOf(v["freeTier"]?.string ?: "") }.getOrDefault(FreeTier.UNKNOWN),
            rateLimitNote = v["rateLimitNote"]?.string, dailyRequestLimit = v["dailyRequestLimit"]?.int,
            temperature = v["temperature"]?.number, maxOutputTokens = v["maxOutputTokens"]?.int,
            enabled = v["enabled"]?.bool ?: true, dataPolicyAcknowledged = v["dataPolicyAcknowledged"]?.bool ?: false,
        )
    }
}
