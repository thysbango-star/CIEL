package dev.ciel.core.json

/**
 * A small, dependency-free JSON model, parser and writer.
 *
 * Ciel's pure-Kotlin modules deliberately avoid third-party serialization libraries so they
 * can be compiled and unit-tested on any JVM, and so every provider adapter can map its own
 * wire format explicitly (providers do not share identical schemas).
 */
sealed class JsonValue {
    open operator fun get(key: String): JsonValue? = null
    open operator fun get(index: Int): JsonValue? = null

    val string: String? get() = (this as? JsonString)?.value
    val number: Double? get() = (this as? JsonNumber)?.value
    val int: Int? get() = (this as? JsonNumber)?.value?.toInt()
    val long: Long? get() = (this as? JsonNumber)?.value?.toLong()
    val bool: Boolean? get() = (this as? JsonBool)?.value
    val obj: JsonObject? get() = this as? JsonObject
    val array: JsonArray? get() = this as? JsonArray
    val isNull: Boolean get() = this is JsonNull

    override fun toString(): String = Json.write(this)
}

object JsonNull : JsonValue()
data class JsonBool(val value: Boolean) : JsonValue() { override fun toString() = Json.write(this) }
data class JsonNumber(val value: Double) : JsonValue() { override fun toString() = Json.write(this) }
data class JsonString(val value: String) : JsonValue() { override fun toString() = Json.write(this) }

class JsonArray(val items: List<JsonValue>) : JsonValue(), List<JsonValue> by items {
    override fun get(index: Int): JsonValue = items[index]
    override fun equals(other: Any?) = other is JsonArray && other.items == items
    override fun hashCode() = items.hashCode()
    override fun toString() = Json.write(this)
}

class JsonObject(val fields: Map<String, JsonValue>) : JsonValue() {
    override fun get(key: String): JsonValue? = fields[key]
    val keys: Set<String> get() = fields.keys
    override fun equals(other: Any?) = other is JsonObject && other.fields == fields
    override fun hashCode() = fields.hashCode()
    override fun toString() = Json.write(this)
}

class JsonParseException(message: String, val position: Int) : RuntimeException("$message at position $position")

/** Builder DSL: `json { "model" to "x"; "messages" to arr(...) }` */
class JsonObjectBuilder {
    private val map = LinkedHashMap<String, JsonValue>()
    infix fun String.to(value: Any?) { map[this] = Json.of(value) }
    /** Only adds the field when [value] is non-null. */
    fun opt(key: String, value: Any?) { if (value != null) map[key] = Json.of(value) }
    fun build() = JsonObject(map)
}

fun json(block: JsonObjectBuilder.() -> Unit): JsonObject = JsonObjectBuilder().apply(block).build()
fun jsonArrayOf(vararg values: Any?): JsonArray = JsonArray(values.map { Json.of(it) })

object Json {
    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    fun parseOrNull(text: String): JsonValue? = try { parse(text) } catch (_: JsonParseException) { null }

    /** Converts common Kotlin values into [JsonValue]. */
    fun of(value: Any?): JsonValue = when (value) {
        null -> JsonNull
        is JsonValue -> value
        is String -> JsonString(value)
        is Boolean -> JsonBool(value)
        is Number -> JsonNumber(value.toDouble())
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to of(v) })
        is Iterable<*> -> JsonArray(value.map { of(it) })
        is Array<*> -> JsonArray(value.map { of(it) })
        is Enum<*> -> JsonString(value.name)
        else -> throw IllegalArgumentException("Unsupported JSON value type: ${value::class.simpleName}")
    }

    fun write(value: JsonValue): String = StringBuilder().also { writeTo(value, it) }.toString()

    private fun writeTo(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonBool -> out.append(value.value)
            is JsonNumber -> {
                val d = value.value
                if (d.isNaN() || d.isInfinite()) out.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) out.append(d.toLong())
                else out.append(d)
            }
            is JsonString -> quote(value.value, out)
            is JsonArray -> {
                out.append('[')
                value.items.forEachIndexed { i, v -> if (i > 0) out.append(','); writeTo(v, out) }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    quote(k, out); out.append(':'); writeTo(v, out)
                }
                out.append('}')
            }
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) fail("Unexpected trailing content")
            return v
        }

        private fun fail(msg: String): Nothing = throw JsonParseException(msg, i)

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun parseValue(): JsonValue {
            if (i >= s.length) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("Expected $word")
            i += word.length
            return v
        }

        private fun parseObject(): JsonObject {
            i++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(map) }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("Expected object key")
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("Expected ':'")
                i++
                skipWs()
                map[key] = parseValue()
                skipWs()
                if (i >= s.length) fail("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject(map) }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonArray {
            i++ // [
            val list = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(list) }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                if (i >= s.length) fail("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(list) }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                                i += 4
                            }
                            else -> fail("Unknown escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonNumber {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            return JsonNumber(text.toDoubleOrNull() ?: fail("Bad number '$text'"))
        }
    }
}
