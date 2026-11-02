package dev.ciel.core

/**
 * Removes secrets from text before it reaches any log, error message or crash report.
 * Requirement: never expose credentials in logs.
 */
object Redaction {
    /** Patterns whose first group is a label to keep (e.g. "Bearer "). */
    private val labelled = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}"),
        Regex("(?i)((?:api[_-]?key|x-goog-api-key|authorization|token|secret)[\"']?\\s*[:=]\\s*[\"']?)[^\"'\\s,&}]{6,}"),
        Regex("(?i)([?&]key=)[^&\\s]+"),
    )

    /** Patterns that match a whole well-known key format. */
    private val whole = listOf(
        Regex("\\bsk-[A-Za-z0-9_\\-]{10,}"),
        Regex("\\bgsk_[A-Za-z0-9]{10,}"),
        Regex("\\bAIza[0-9A-Za-z_\\-]{20,}"),
    )

    fun redact(text: String, knownSecrets: Collection<String> = emptyList()): String {
        var out = text
        for (secret in knownSecrets) if (secret.length >= 4) out = out.replace(secret, "[REDACTED]")
        for (p in labelled) out = p.replace(out, "$1[REDACTED]")
        for (p in whole) out = p.replace(out, "[REDACTED]")
        return out
    }
}
