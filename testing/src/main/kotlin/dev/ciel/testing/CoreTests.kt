package dev.ciel.testing

import com.sun.net.httpserver.HttpServer
import dev.ciel.ai.AuthStyle
import dev.ciel.ai.ChatMessage
import dev.ciel.ai.ChatRequest
import dev.ciel.ai.FreeTier
import dev.ciel.ai.GeminiProvider
import dev.ciel.ai.ImageInput
import dev.ciel.ai.OpenAiCompatibleProvider
import dev.ciel.ai.ProtocolKind
import dev.ciel.ai.ProviderConfig
import dev.ciel.ai.ProviderException
import dev.ciel.ai.StreamEvent
import dev.ciel.ai.ToolCall
import dev.ciel.ai.ToolSpec
import dev.ciel.core.Redaction
import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpResponse
import dev.ciel.core.http.NetworkError
import dev.ciel.core.http.UrlConnectionTransport
import dev.ciel.core.json.Json
import dev.ciel.core.json.JsonParseException
import dev.ciel.core.json.json
import kotlinx.coroutines.flow.toList
import java.net.InetSocketAddress

fun coreTests() = T.suite("core") {
    T.test("json round-trips nested structures, escapes and unicode") {
        val text = """{"a":[1,2.5,-3e2,true,false,null],"s":"q\"uote\\né\n","o":{"x":{}}}"""
        val v = Json.parse(text)
        eq(-300.0, v["a"]!![2]!!.number)
        eq("q\"uote\\né\n", v["s"]!!.string)
        eq(v, Json.parse(v.toString()), "re-parse")
    }
    T.test("json rejects malformed input with a position") {
        throws<JsonParseException> { Json.parse("{\"a\":1,}") }
        throws<JsonParseException> { Json.parse("[1 2]") }
        check(Json.parseOrNull("nope") == null)
    }
    T.test("json builder skips null optionals and writes integers without decimals") {
        eq("""{"n":3,"t":"x"}""", json { "n" to 3; opt("missing", null); "t" to "x" }.toString())
    }
    T.test("redaction hides keys in headers, bodies and known formats") {
        val leaked = "Authorization: Bearer sk-abcdefghijklmnop12345 url?key=AIzaSyA1234567890123456789012 {\"api_key\":\"gsk_ABCDEFGHIJKLMNOP\"}"
        val r = Redaction.redact(leaked, listOf("mysecretkey"))
        check("sk-abcdefghijklmnop12345" !in r && "AIzaSy" !in r && "gsk_ABCDEF" !in r) { r }
        eq("token [REDACTED] here", Redaction.redact("token mysecretkey here", listOf("mysecretkey")))
    }
    T.test("UrlConnectionTransport streams lines from a real local server and classifies offline errors") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sse") { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write("data: one\n\ndata: two\n\n".toByteArray()) }
        }
        server.start()
        try {
            val lines = UrlConnectionTransport().streamLines(HttpRequest("http://127.0.0.1:${server.address.port}/sse", "GET")).toList()
            eq(listOf("data: one", "", "data: two", ""), lines)
        } finally { server.stop(0) }
        throws<NetworkError.Offline> { kotlinx.coroutines.runBlocking { UrlConnectionTransport().execute(HttpRequest("http://127.0.0.1:1/x", "GET")) } }
    }
}

private fun openAiConfig(tools: Boolean = true, vision: Boolean = false) = ProviderConfig(
    "p1", "Test", ProtocolKind.OPENAI_CHAT_COMPLETIONS, "https://example.test/v1", "m-1",
    supportsTools = tools, supportsVision = vision)

fun providerTests() = T.suite("ai-providers") {
    T.test("OpenAI-compatible: request body maps tools, images, tool results; parses tool calls") {
        val fake = FakeTransport { req ->
            val body = Json.parse(req.body!!)
            eq("Bearer KEY", req.headers["Authorization"])
            eq("search", body["tools"]!![0]!!["function"]!!["name"]!!.string)
            val userContent = body["messages"]!![1]!!["content"]!!
            eq("image_url", userContent[1]!!["type"]!!.string)
            eq("call_9", body["messages"]!![3]!!["tool_call_id"]!!.string)
            HttpResponse(200, """{"model":"m-1","choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"search","arguments":"{\"q\":\"x\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""")
        }
        val p = OpenAiCompatibleProvider(openAiConfig(vision = true), "KEY", fake)
        val r = p.chat(ChatRequest(listOf(
            ChatMessage.system("sys"),
            ChatMessage.user("look", listOf(ImageInput("image/png", "AAAA"))),
            ChatMessage.assistant("", listOf(ToolCall("call_9", "search", "{}"))),
            ChatMessage.toolResult("call_9", "search", "result"),
        ), tools = listOf(ToolSpec("search", "d", json { "type" to "object" }))))
        eq(1, r.toolCalls.size); eq("search", r.toolCalls[0].name); eq("{\"q\":\"x\"}", r.toolCalls[0].argumentsJson)
        eq(10, r.usage?.inputTokens)
    }
    T.test("OpenAI-compatible: tools are not sent to a model marked without tool support") {
        val fake = FakeTransport { req -> check(Json.parse(req.body!!)["tools"] == null); HttpResponse(200, """{"choices":[{"message":{"content":"hi"}}]}""") }
        eq("hi", OpenAiCompatibleProvider(openAiConfig(tools = false), "K", fake).chat(ChatRequest(listOf(ChatMessage.user("x")), tools = listOf(ToolSpec("t", "d", json { })))).text)
    }
    T.test("OpenAI-compatible: streaming assembles text and fragmented tool-call arguments") {
        val fake = FakeTransport { HttpResponse(500, "") }
        fake.streamHandler = { listOf(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":"{\"expr"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ession\":\"1+1\"}"}}]},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        ) }
        val events = OpenAiCompatibleProvider(openAiConfig(), "K", fake).stream(ChatRequest(listOf(ChatMessage.user("x")))).toList()
        eq(listOf("Hel", "lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        val done = events.last() as StreamEvent.Completed
        eq("Hello", done.result.text); eq("{\"expression\":\"1+1\"}", done.result.toolCalls.single().argumentsJson)
        eq("text/event-stream", fake.requests.single().headers["Accept"])
    }
    T.test("OpenAI-compatible: 401 gives a clear message without leaking the key") {
        val fake = FakeTransport { HttpResponse(401, """{"error":{"message":"Incorrect API key provided: sk-secretsecret123456"}}""") }
        val e = throws<ProviderException> { kotlinx.coroutines.runBlocking { OpenAiCompatibleProvider(openAiConfig(), "sk-secretsecret123456", fake).chat(ChatRequest(listOf(ChatMessage.user("x")))) } }
        check(e.isAuthError && "Authentication failed" in e.message!! && "secretsecret" !in e.message!!) { e.message!! }
    }
    T.test("OpenAI-compatible: model list marks free only from real pricing data") {
        val fake = FakeTransport { HttpResponse(200, """{"data":[
            {"id":"a:free","context_length":32000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools"]},
            {"id":"b","pricing":{"prompt":"0.000001","completion":"0.000002"}},
            {"id":"c","context_window":8192}]}""") }
        val models = OpenAiCompatibleProvider(openAiConfig(), "K", fake).listModels().associateBy { it.id }
        eq(FreeTier.FREE, models["a:free"]!!.freeTier); eq(true, models["a:free"]!!.supportsVision); eq(true, models["a:free"]!!.supportsTools)
        eq(FreeTier.PAID, models["b"]!!.freeTier)
        eq(FreeTier.UNKNOWN, models["c"]!!.freeTier); eq(8192, models["c"]!!.contextWindow)
    }
    T.test("Custom header auth and extra headers are applied") {
        val cfg = openAiConfig().copy(authStyle = AuthStyle.CUSTOM_HEADER, authHeaderName = "api-key", extraHeaders = mapOf("HTTP-Referer" to "ciel"))
        val fake = FakeTransport { req -> eq("K", req.headers["api-key"]); eq("ciel", req.headers["HTTP-Referer"]); check(req.headers["Authorization"] == null); HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""") }
        OpenAiCompatibleProvider(cfg, "K", fake).chat(ChatRequest(listOf(ChatMessage.user("x"))))
    }

    val gem = ProviderConfig("g", "Gemini", ProtocolKind.GEMINI_GENERATE_CONTENT, "https://gl.test/v1beta", "gemini-x", AuthStyle.CUSTOM_HEADER, supportsTools = true, supportsVision = true)
    T.test("Gemini: system instruction, role mapping, merged turns, functionCall/functionResponse") {
        val fake = FakeTransport { req ->
            check(req.url.endsWith("/models/gemini-x:generateContent")) { req.url }
            eq("KEY", req.headers["x-goog-api-key"])
            val b = Json.parse(req.body!!)
            eq("be ciel", b["systemInstruction"]!!["parts"]!![0]!!["text"]!!.string)
            val contents = b["contents"]!!.array!!
            eq(listOf("user", "model", "user"), contents.map { it["role"]!!.string })
            eq("calc", contents[1]["parts"]!![0]!!["functionCall"]!!["name"]!!.string)
            eq(2.0, contents[2]["parts"]!![0]!!["functionResponse"]!!["response"]!!["result"]!!.number)
            eq("calc", b["tools"]!![0]!!["functionDeclarations"]!![0]!!["name"]!!.string)
            HttpResponse(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"Answer. "},{"functionCall":{"name":"calc","args":{"expression":"2*3"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}""")
        }
        val r = GeminiProvider(gem, "KEY", fake).chat(ChatRequest(listOf(
            ChatMessage.system("be ciel"), ChatMessage.user("1+1?"),
            ChatMessage.assistant("", listOf(ToolCall("x", "calc", "{\"expression\":\"1+1\"}"))),
            ChatMessage.toolResult("x", "calc", "2"),
        ), tools = listOf(ToolSpec("calc", "d", json { "type" to "object" }))))
        eq("Answer. ", r.text); eq("calc", r.toolCalls.single().name); eq("{\"expression\":\"2*3\"}", r.toolCalls.single().argumentsJson); eq(7, r.usage?.inputTokens)
    }
    T.test("Gemini: streaming via SSE and blocked prompts produce clear errors") {
        val fake = FakeTransport { HttpResponse(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        fake.streamHandler = { req -> check(req.url.contains(":streamGenerateContent?alt=sse")); listOf(
            """data: {"candidates":[{"content":{"parts":[{"text":"Re"}]}}]}""",
            """data: {"candidates":[{"content":{"parts":[{"text":"port."}]},"finishReason":"STOP"}]}""") }
        val events = GeminiProvider(gem, "K", fake).stream(ChatRequest(listOf(ChatMessage.user("x")))).toList()
        eq("Report.", (events.last() as StreamEvent.Completed).result.text)
        val e = throws<ProviderException> { kotlinx.coroutines.runBlocking { GeminiProvider(gem, "K", fake).chat(ChatRequest(listOf(ChatMessage.user("x")))) } }
        check("SAFETY" in e.message!!)
    }
    T.test("Gemini: model list keeps only generateContent models and reads context limits") {
        val fake = FakeTransport { HttpResponse(200, """{"models":[{"name":"models/gemini-a","displayName":"A","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}""") }
        val list = GeminiProvider(gem, "K", fake).listModels()
        eq(listOf("gemini-a"), list.map { it.id }); eq(1048576, list[0].contextWindow); eq(FreeTier.UNKNOWN, list[0].freeTier)
    }
}
