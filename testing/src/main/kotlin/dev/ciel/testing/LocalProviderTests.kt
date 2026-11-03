package dev.ciel.testing

import com.sun.net.httpserver.HttpServer
import dev.ciel.ai.ConnectResult
import dev.ciel.ai.ConnectionWizard
import dev.ciel.ai.InferenceLocation
import dev.ciel.ai.KeyDetector
import dev.ciel.ai.ModelInfo
import dev.ciel.ai.ModelPicker
import dev.ciel.ai.OllamaClient
import dev.ciel.ai.OllamaDiscovery
import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpResponse
import dev.ciel.core.http.HttpTransport
import dev.ciel.core.http.UrlConnectionTransport
import kotlinx.coroutines.flow.Flow
import java.net.InetSocketAddress

/** A minimal Ollama: version, tags, show (with capabilities), ps and OpenAI-compatible chat. */
private fun fakeOllama(withCapabilities: Boolean = true, chatWorks: Boolean = true): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    fun reply(path: String, body: () -> String) = server.createContext(path) { ex ->
        val requestBody = ex.requestBody.bufferedReader().readText()
        val out = if (path == "/api/show") {
            val caps = when {
                !withCapabilities -> null
                "llava" in requestBody -> "[\"completion\",\"vision\"]"
                "embed" in requestBody -> "[\"embedding\"]"
                else -> "[\"completion\",\"tools\"]"
            }
            "{" + (caps?.let { "\"capabilities\":$it," } ?: "") + "\"model_info\":{\"llama.context_length\":131072}}"
        } else body()
        val code = if (path == "/v1/chat/completions" && !chatWorks) 500 else 200
        val bytes = out.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
    }
    reply("/api/version") { "{\"version\":\"0.9.0\"}" }
    reply("/api/tags") {
        """{"models":[
          {"name":"llama3.2:3b","size":2019393189,"details":{"family":"llama","families":["llama"],"parameter_size":"3.2B"}},
          {"name":"qwen2.5:14b","size":8988124069,"details":{"family":"qwen2","families":["qwen2"],"parameter_size":"14.8B"}},
          {"name":"llava:7b","size":4733363377,"details":{"family":"llama","families":["llama","clip"],"parameter_size":"7B"}},
          {"name":"nomic-embed-text:latest","size":274302450,"details":{"family":"nomic-bert","families":["nomic-bert"],"parameter_size":"137M"}}]}"""
    }
    reply("/api/show") { "" }
    reply("/api/ps") { "{\"models\":[]}" }
    reply("/v1/chat/completions") { if (chatWorks) "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}" else "{\"error\":\"model requires more system memory\"}" }
    server.start()
    return server
}

private class ThrowingTransport(private val e: Exception) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = throw e
    override fun streamLines(request: HttpRequest): Flow<String> = throw e
}

fun localProviderTests() = T.suite("local-providers") {
    T.test("Ollama: finds installed models, picks fast/reasoning/vision, enables tools only where the server reports them") {
        val server = fakeOllama()
        try {
            val r = ConnectionWizard(UrlConnectionTransport()).connect("", "ollama1", presetKey = "ollama", customBaseUrl = "127.0.0.1:${server.address.port}")
            check(r is ConnectResult.Success) { r.toString() }
            r as ConnectResult.Success
            val byRole = r.configs.associateBy { it.id.substringAfter(':') }
            eq("llama3.2:3b", byRole["fast"]!!.model); eq(true, byRole["fast"]!!.supportsTools)
            eq("qwen2.5:14b", byRole["strong"]!!.model)
            eq("llava:7b", byRole["vision"]!!.model); eq(true, byRole["vision"]!!.supportsVision); eq(false, byRole["vision"]!!.supportsTools)
            check(r.configs.none { "embed" in it.model })
            check(r.configs.all { it.baseUrl.endsWith("/v1") && it.authStyle == dev.ciel.ai.AuthStyle.NONE })
            check(r.details.first().contains("on this phone")) { r.details.toString() } // 127.0.0.1 = this device
        } finally { server.stop(0) }
    }

    T.test("Ollama: old servers without capability data get tools off (no HTTP 400s), and failures are explained") {
        val server = fakeOllama(withCapabilities = false)
        try {
            val r = ConnectionWizard(UrlConnectionTransport()).connectOllama("http://127.0.0.1:${server.address.port}/v1", "o")
            r as ConnectResult.Success
            check(r.configs.all { !it.supportsTools }); check(r.details.any { "doesn't report capabilities" in it })
            eq("llava:7b", r.configs.first { it.supportsVision }.model) // vision inferred from the clip family
        } finally { server.stop(0) }
        val broken = fakeOllama(chatWorks = false)
        try {
            val r = ConnectionWizard(UrlConnectionTransport()).connectOllama("127.0.0.1:${broken.address.port}", "o")
            check(r is ConnectResult.Failure && "didn't answer" in r.message) { r.toString() }
        } finally { broken.stop(0) }
        val none = ConnectionWizard(UrlConnectionTransport()).connectOllama("127.0.0.1:1", "o")
        check(none is ConnectResult.Failure && "OLLAMA_HOST=0.0.0.0" in none.howToFix)
    }

    T.test("crash regression: any network exception becomes a clear failure, never an uncaught crash") {
        val cleartext = java.net.UnknownServiceException("CLEARTEXT communication to 192.168.1.5 not permitted by network security policy")
        val r1 = ConnectionWizard(ThrowingTransport(cleartext)).connect("", "c", presetKey = "custom", customBaseUrl = "http://192.168.1.5:11434/v1")
        check(r1 is ConnectResult.Failure) { r1.toString() }
        val r2 = ConnectionWizard(ThrowingTransport(java.net.NoRouteToHostException("No route to host"))).connectOllama("192.168.1.5", "c")
        check(r2 is ConnectResult.Failure)
        val e = throws<dev.ciel.core.http.NetworkError> {
            kotlinx.coroutines.runBlocking { UrlConnectionTransport().execute(HttpRequest("http://256.1.1.1:11434/api/version", "GET", timeoutMillis = 1000)) }
        }
        check(e.message!!.isNotBlank())
    }

    T.test("addresses: normalisation, where inference runs, and no plain HTTP over the internet") {
        val c = OllamaClient(UrlConnectionTransport())
        eq("http://192.168.1.5:11434", c.normalise("192.168.1.5"))
        eq("http://pc.local:11434", c.normalise("http://pc.local:11434/v1/"))
        eq("http://10.0.0.2:8080", c.normalise("10.0.0.2:8080"))
        eq(InferenceLocation.ON_DEVICE, InferenceLocation.of("http://127.0.0.1:11434/v1"))
        eq(InferenceLocation.LOCAL_NETWORK, InferenceLocation.of("http://192.168.1.5:11434/v1"))
        eq(InferenceLocation.CLOUD, InferenceLocation.of("https://integrate.api.nvidia.com/v1"))
        check(InferenceLocation.plainHttpAllowed("http://172.20.1.1:11434")); check(!InferenceLocation.plainHttpAllowed("http://8.8.8.8:11434"))
        val r = kotlinx.coroutines.runBlocking { ConnectionWizard(UrlConnectionTransport()).connect("", "x", presetKey = "custom", customBaseUrl = "http://example.com/v1") }
        check(r is ConnectResult.Failure && "unencrypted" in r.message)
    }

    T.test("discovery: candidate order and a scan that finds a real server") {
        val list = OllamaDiscovery.candidates("192.168.1.42")
        eq("127.0.0.1", list.first()); check("192.168.1.1" in list && "192.168.1.42" !in list && list.size in 250..260)
        val server = fakeOllama()
        try {
            val found = OllamaDiscovery.scan(OllamaClient(UrlConnectionTransport()), listOf("127.0.0.1:1", "127.0.0.1:${server.address.port}"), timeoutMs = 1500)
            eq(listOf("http://127.0.0.1:${server.address.port}"), found)
        } finally { server.stop(0) }
    }

    T.test("NVIDIA: nvapi- keys are recognised; embedding/retrieval/image models are not used for chat") {
        eq("nvidia", KeyDetector.candidates("nvapi-abc123").single().key)
        val models = listOf("nvidia/nv-embedqa-e5-v5", "nvidia/llama-3.2-nv-rerankqa-1b-v2", "meta/llama-3.1-8b-instruct",
            "meta/llama-3.3-70b-instruct", "meta/llama-3.2-11b-vision-instruct", "stabilityai/stable-diffusion-xl").map { ModelInfo(it) }
        val plan = ModelPicker.plan("nvidia", models)
        check(plan.strongCandidates.none { "embed" in it.id || "rerank" in it.id || "diffusion" in it.id }) { plan.toString() }
        eq("meta/llama-3.1-8b-instruct", plan.fastCandidates.first().id)
        eq("meta/llama-3.2-11b-vision-instruct", plan.visionCandidates.first().id)
    }

    T.test("internet: built-in search works with no key (instant answer + Wikipedia); Ollama web search maps results and key errors") {
        val fake = FakeTransport { r ->
            when {
                "duckduckgo" in r.url -> HttpResponse(200, """{"Heading":"Cookie Run: Kingdom","AbstractText":"A 2021 mobile game by Devsisters.","AbstractURL":"https://en.wikipedia.org/wiki/Cookie_Run:_Kingdom","RelatedTopics":[]}""")
                "wikipedia" in r.url -> HttpResponse(200, """{"query":{"search":[{"title":"Cookie Run: Kingdom","snippet":"<span>Cookie</span> Run"},{"title":"Devsisters","snippet":"Korean company"}]}}""")
                else -> HttpResponse(404, "")
            }
        }
        val hits = dev.ciel.research.BuiltInSearch(fake).search("cookie run kingdom", 5)
        eq("Cookie Run: Kingdom", hits.first().title); check(hits.any { "Devsisters" in it.title }) { hits.toString() }
        eq(hits.size, hits.distinctBy { it.url }.size) // the abstract and Wikipedia hit for the same page aren't duplicated
        check(fake.requests.all { r -> r.headers["User-Agent"]?.contains("Ciel") == true })
        val ok = FakeTransport { HttpResponse(200, """{"results":[{"title":"News","url":"https://example.com/a","content":"Text"}]}""") }
        eq("https://example.com/a", dev.ciel.research.OllamaWebSearch("k", ok).search("x", 3).single().url)
        eq("Bearer k", ok.requests.single().headers["Authorization"])
        val bad = FakeTransport { HttpResponse(401, "") }
        val e = throws<dev.ciel.research.SearchException> { kotlinx.coroutines.runBlocking { dev.ciel.research.OllamaWebSearch("k", bad).search("x", 3) } }
        check("ollama.com/settings/keys" in e.message!!)
    }
}
