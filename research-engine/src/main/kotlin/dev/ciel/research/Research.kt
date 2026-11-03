package dev.ciel.research

import dev.ciel.ai.ToolSpec
import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpTransport
import dev.ciel.core.http.NetworkError
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonObject
import dev.ciel.core.json.json
import dev.ciel.skills.Capability
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.int
import dev.ciel.skills.reqStr
import java.net.URI
import java.net.URLEncoder

data class SearchHit(val title: String, val url: String, val snippet: String)

enum class SearchProviderKind(val label: String, val needsKey: Boolean, val keyUrl: String? = null) {
    /** Works with no setup: Wikipedia search + DuckDuckGo instant answers (both public, keyless APIs). */
    BUILT_IN("Built-in (no key)", false),
    /** Full web search through an ollama.com account (free tier available). */
    OLLAMA("Ollama web search", true, "https://ollama.com/settings/keys"),
    BRAVE("Brave Search API", true, "https://brave.com/search/api/"),
    TAVILY("Tavily", true, "https://app.tavily.com"),
    SEARXNG("SearXNG (self-hosted)", false),
}

data class SearchConfig(val kind: SearchProviderKind, val apiKey: String?, val baseUrl: String? = null)

interface SearchProvider {
    suspend fun search(query: String, count: Int): List<SearchHit>
}

class SearchException(message: String) : Exception(message)

private const val UA = "CielAssistant/0.6 (Android personal assistant; https://github.com/thysbango-star/CIEL)"

/**
 * Keyless search: DuckDuckGo's Instant Answer API (a direct answer/summary when one exists) plus
 * Wikipedia's search API. Not a full web index — for that, use Ollama, Brave, Tavily or SearXNG.
 */
class BuiltInSearch(private val http: HttpTransport) : SearchProvider {
    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        runCatching {
            val r = http.execute(HttpRequest("https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1&t=ciel", "GET", mapOf("User-Agent" to UA, "Accept" to "application/json"), timeoutMillis = 15_000))
            if (r.isSuccess) Json.parseOrNull(r.body)?.let { o ->
                val abstract = o["AbstractText"]?.string.orEmpty()
                val url = o["AbstractURL"]?.string.orEmpty()
                if (abstract.isNotBlank() && url.isNotBlank()) out += SearchHit(o["Heading"]?.string ?: query, url, abstract.take(600))
                o["Answer"]?.string?.takeIf { it.isNotBlank() }?.let { out += SearchHit("Instant answer", "https://duckduckgo.com/?q=${enc(query)}", it) }
                o["RelatedTopics"]?.array.orEmpty().take(3).forEach { t ->
                    val text = t["Text"]?.string; val u = t["FirstURL"]?.string
                    if (!text.isNullOrBlank() && !u.isNullOrBlank()) out += SearchHit(text.substringBefore(" - ").take(80), u, text.take(300))
                }
            }
        }
        val r = http.execute(HttpRequest("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=${count.coerceIn(1, 10)}&srsearch=${enc(query)}",
            "GET", mapOf("User-Agent" to UA, "Accept" to "application/json"), timeoutMillis = 15_000))
        if (!r.isSuccess && out.isEmpty()) throw SearchException("Built-in search failed (HTTP ${r.code}).")
        if (r.isSuccess) Json.parseOrNull(r.body)?.get("query")?.get("search")?.array.orEmpty().forEach { h ->
            val title = h["title"]?.string ?: return@forEach
            out += SearchHit("$title — Wikipedia", "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')).replace("%2F", "/"), stripTags(h["snippet"]?.string ?: ""))
        }
        return out.distinctBy { it.url }.take(count)
    }
}

/** Ollama's web search API: POST https://ollama.com/api/web_search with an ollama.com API key. */
class OllamaWebSearch(private val key: String, private val http: HttpTransport) : SearchProvider {
    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val body = json { "query" to query; "max_results" to count.coerceIn(1, 10) }
        val r = http.execute(HttpRequest("https://ollama.com/api/web_search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"), body.toString(), 30_000))
        if (r.code == 401 || r.code == 403) throw SearchException("Ollama rejected the web-search key. Create one at ollama.com/settings/keys.")
        if (!r.isSuccess) throw SearchException("Ollama web search failed (HTTP ${r.code}).")
        return Json.parse(r.body)["results"]?.array.orEmpty().mapNotNull {
            SearchHit(it["title"]?.string ?: "", it["url"]?.string ?: return@mapNotNull null, (it["content"]?.string ?: "").take(500))
        }
    }
}

/** Brave Search API: GET https://api.search.brave.com/res/v1/web/search, header X-Subscription-Token. */
class BraveSearch(private val key: String, private val http: HttpTransport) : SearchProvider {
    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=${enc(query)}&count=${count.coerceIn(1, 20)}"
        val r = http.execute(HttpRequest(url, "GET", mapOf("Accept" to "application/json", "X-Subscription-Token" to key)))
        if (!r.isSuccess) throw SearchException("Brave search failed (HTTP ${r.code}).")
        return Json.parse(r.body)["web"]?.get("results")?.array.orEmpty().mapNotNull {
            SearchHit(it["title"]?.string ?: return@mapNotNull null, it["url"]?.string ?: return@mapNotNull null, stripTags(it["description"]?.string ?: ""))
        }
    }
}

/** Tavily: POST https://api.tavily.com/search with Bearer key. */
class TavilySearch(private val key: String, private val http: HttpTransport) : SearchProvider {
    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val body = json { "query" to query; "max_results" to count.coerceIn(1, 20) }
        val r = http.execute(HttpRequest("https://api.tavily.com/search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"), body.toString()))
        if (!r.isSuccess) throw SearchException("Tavily search failed (HTTP ${r.code}).")
        return Json.parse(r.body)["results"]?.array.orEmpty().mapNotNull {
            SearchHit(it["title"]?.string ?: "", it["url"]?.string ?: return@mapNotNull null, it["content"]?.string?.take(400) ?: "")
        }
    }
}

/** SearXNG instance with JSON output enabled: GET {base}/search?q=..&format=json */
class SearxngSearch(private val baseUrl: String, private val http: HttpTransport) : SearchProvider {
    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val r = http.execute(HttpRequest("${baseUrl.trimEnd('/')}/search?q=${enc(query)}&format=json", "GET", mapOf("Accept" to "application/json")))
        if (!r.isSuccess) throw SearchException("SearXNG search failed (HTTP ${r.code}). Is JSON format enabled on the instance?")
        return Json.parse(r.body)["results"]?.array.orEmpty().take(count).mapNotNull {
            SearchHit(it["title"]?.string ?: "", it["url"]?.string ?: return@mapNotNull null, it["content"]?.string ?: "")
        }
    }
}

object SearchProviders {
    fun create(c: SearchConfig, http: HttpTransport): SearchProvider = when (c.kind) {
        SearchProviderKind.BUILT_IN -> BuiltInSearch(http)
        SearchProviderKind.OLLAMA -> OllamaWebSearch(c.apiKey ?: throw SearchException("Ollama web search needs an ollama.com API key."), http)
        SearchProviderKind.BRAVE -> BraveSearch(c.apiKey ?: throw SearchException("Brave needs an API key."), http)
        SearchProviderKind.TAVILY -> TavilySearch(c.apiKey ?: throw SearchException("Tavily needs an API key."), http)
        SearchProviderKind.SEARXNG -> SearxngSearch(c.baseUrl ?: throw SearchException("SearXNG needs a base URL."), http)
    }
}

data class FetchedPage(val url: String, val finalStatus: Int, val title: String?, val text: String)

/** Downloads a page and extracts readable text. Records the real status — never pretends. */
class PageFetcher(private val http: HttpTransport) {
    val transport: HttpTransport get() = http
    suspend fun fetch(url: String, maxChars: Int = 12_000): FetchedPage {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw SearchException("Not a valid web address: $url")
        if (isPrivateHost(uri.host)) throw SearchException("Refusing to fetch a local/private network address.")
        val r = try {
            http.execute(HttpRequest(url, "GET", mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) CielAssistant/0.1", "Accept" to "text/html,text/plain;q=0.9"), timeoutMillis = 20_000))
        } catch (e: NetworkError) {
            throw SearchException("Could not access $url: ${e.message}")
        }
        if (!r.isSuccess) throw SearchException("Could not access $url (HTTP ${r.code}).")
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(r.body)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }
        return FetchedPage(url, r.code, title, htmlToText(r.body).take(maxChars))
    }

    companion object {
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase()
            return h == "localhost" || h.endsWith(".local") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h == "0.0.0.0" || h.startsWith("169.254.") || h == "[::1]"
        }

        fun htmlToText(html: String): String {
            var s = html
            s = s.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form)[^>]*>.*?</\\1>"), " ")
            s = s.replace(Regex("(?is)<!--.*?-->"), " ")
            s = s.replace(Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>"), "\n")
            s = s.replace(Regex("(?is)<[^>]+>"), " ")
            s = decodeEntities(s)
            s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            s = s.replace(Regex("\\n\\s*\\n+"), "\n\n")
            return s.lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n")
        }

        fun decodeEntities(s: String): String = s
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
    }
}

/**
 * Heuristic source assessment. It is explicitly a heuristic and labelled as such to the model
 * and user; the model is told to cross-check claims across sources.
 */
object SourceAssessor {
    data class Assessment(val domain: String, val tier: String, val notes: List<String>)

    private val reference = setOf("wikipedia.org", "britannica.com", "nature.com", "science.org", "who.int", "nih.gov", "reuters.com", "apnews.com", "bbc.co.uk", "bbc.com", "developer.android.com", "arxiv.org")

    fun assess(url: String): Assessment {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: "unknown"
        val notes = mutableListOf<String>()
        if (!url.startsWith("https://")) notes += "not served over HTTPS"
        val tier = when {
            host.endsWith(".gov") || host.contains(".gov.") || host.endsWith(".edu") || host.contains(".ac.") -> "institutional"
            reference.any { host == it || host.endsWith(".$it") } -> "established reference/news"
            host.contains("blogspot") || host.contains("medium.com") || host.contains("substack") -> "personal publication"
            host.contains("reddit") || host.contains("quora") || host.contains("forum") -> "user-generated forum"
            else -> "unclassified"
        }
        return Assessment(host, tier, notes)
    }
}

/** Research skill: search + open pages. Content returned is UNTRUSTED (prompt-injection safe framing). */
class ResearchSkill(
    private val providerFactory: () -> SearchProvider?,
    private val fetcher: PageFetcher,
) : Skill {
    override val id = "research"
    override val displayName = "Research"
    override val description = "Search the internet, read pages, compare and cite sources."
    override val requiredCapabilities = setOf(Capability.INTERNET)
    override val tools = listOf(
        ToolSpec("web_search", "Search the web. Returns titles, URLs and snippets with a heuristic source tier.",
            Schema.obj(listOf("query"), "query" to Schema.str("Search query"), "count" to Schema.int("Results, 1-10"))),
        ToolSpec("open_page", "Download a web page and return its readable text. Only claim to have read pages this tool returned successfully.",
            Schema.obj(listOf("url"), "url" to Schema.str("https URL"))),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome {
        if (!ctx.isOnline()) return ToolOutcome.fail("Offline: research needs an internet connection.")
        return when (tool) {
            "web_search" -> {
                val provider = providerFactory() ?: BuiltInSearch(fetcher.transport)
                val hits = try { provider.search(args.reqStr("query"), (args.int("count") ?: 6).coerceIn(1, 10)) } catch (e: SearchException) { return ToolOutcome.fail(e.message ?: "Search failed") }
                catch (e: NetworkError) { return ToolOutcome.fail("Search failed: ${e.message}") }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; return ToolOutcome.fail("Search failed: ${e.message}") }
                if (hits.isEmpty()) ToolOutcome.ok("No results.", true)
                else ToolOutcome.external(hits.mapIndexed { i, h ->
                    val a = SourceAssessor.assess(h.url)
                    "[${i + 1}] ${h.title}\n${h.url}\nSource tier (heuristic): ${a.tier}${if (a.notes.isNotEmpty()) "; " + a.notes.joinToString() else ""}\n${h.snippet}"
                }.joinToString("\n\n"), "web search results")
            }
            "open_page" -> try {
                val page = fetcher.fetch(args.reqStr("url"))
                ToolOutcome.external("Fetched OK (HTTP ${page.finalStatus}). Title: ${page.title ?: "-"}\n\n${page.text}", page.url)
            } catch (e: SearchException) {
                ToolOutcome.fail(e.message ?: "Fetch failed") // the model is told the page was NOT accessed
            }
            else -> ToolOutcome.fail("Unknown tool $tool")
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "")
