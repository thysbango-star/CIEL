package dev.ciel.testing

import dev.ciel.ai.CapabilityTier
import dev.ciel.ai.ConnectResult
import dev.ciel.ai.ConnectionWizard
import dev.ciel.ai.FreeTier
import dev.ciel.ai.KeyDetector
import dev.ciel.ai.ModelInfo
import dev.ciel.ai.ModelPicker
import dev.ciel.core.http.HttpResponse
import dev.ciel.core.json.Json

private val groqModels = """{"data":[
 {"id":"llama-3.1-8b-instant","context_window":131072},
 {"id":"llama-3.3-70b-versatile","context_window":131072},
 {"id":"whisper-large-v3"},
 {"id":"meta-llama/llama-4-scout-17b-16e-instruct","context_window":131072},
 {"id":"llama-guard-4-12b"}]}"""

private val geminiModels = """{"models":[
 {"name":"models/gemini-2.5-flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]},
 {"name":"models/gemini-2.5-pro","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]},
 {"name":"models/gemini-2.0-flash-lite","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]},
 {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
 {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]}]}"""

fun setupTests() = T.suite("auto-setup") {
    T.test("key formats identify only the matching provider; unknown formats ask the user") {
        eq("openrouter", KeyDetector.candidates("sk-or-v1-abc").single().key)
        eq("groq", KeyDetector.candidates("gsk_abc").single().key)
        eq("gemini", KeyDetector.candidates("AIzaSyXYZ").single().key)
        eq("openai", KeyDetector.candidates(" sk-proj-abc ").single().key)
        check(KeyDetector.candidates("abcd1234").isEmpty())
    }
    T.test("whole-word matching: 'gemini' is not a 'mini' model") {
        check(!ModelPicker.hasWord("gemini-2.5-pro", "mini"))
        check(ModelPicker.hasWord("gpt-4o-mini", "mini"))
        check(ModelPicker.hasWord("openai/gpt-4.1-nano", "nano"))
    }
    T.test("model plan: drops non-chat models, picks fast vs strong vs vision sensibly") {
        val groq = dev.ciel.ai.OpenAiCompatibleProvider(dev.ciel.ai.ProviderConfig("g", "g", dev.ciel.ai.ProtocolKind.OPENAI_CHAT_COMPLETIONS, "x", "m"), "k",
            FakeTransport { HttpResponse(200, groqModels) })
        val plan = ModelPicker.plan("groq", groq.listModels())
        eq("llama-3.1-8b-instant", plan.fastCandidates.first().id)
        eq("llama-3.3-70b-versatile", plan.strongCandidates.first().id)
        eq("meta-llama/llama-4-scout-17b-16e-instruct", plan.visionCandidates.first().id)
        check(plan.strongCandidates.none { it.id.contains("whisper") || it.id.contains("guard") })

        val gm = Json.parse(geminiModels)["models"]!!.array!!.map { ModelInfo(it["name"]!!.string!!.removePrefix("models/")) }
        val gp = ModelPicker.plan("gemini", gm)
        eq("gemini-2.5-flash", gp.fastCandidates.first().id)
        eq("gemini-2.5-pro", gp.strongCandidates.first().id)
        check(gp.fastCandidates.none { it.id.contains("tts") || it.id.contains("embedding") })
    }
    T.test("model plan prefers free models when pricing says some are free") {
        val models = listOf(
            ModelInfo("openai/gpt-4o", freeTier = FreeTier.PAID, supportsTools = true, supportsVision = true),
            ModelInfo("meta-llama/llama-3.3-70b-instruct:free", freeTier = FreeTier.FREE, supportsTools = true),
            ModelInfo("google/gemma-3-4b-it:free", freeTier = FreeTier.FREE, supportsTools = true),
        )
        val p = ModelPicker.plan("openrouter", models)
        check(p.strongCandidates.all { it.freeTier == FreeTier.FREE })
        eq("meta-llama/llama-3.3-70b-instruct:free", p.strongCandidates.first().id)
    }
    T.test("connect: Groq key → validated, tested, fast + reasoning + vision configs") {
        val sent = mutableListOf<String>()
        val t = FakeTransport { req ->
            sent += req.url
            if (req.url.endsWith("/models")) HttpResponse(200, groqModels)
            else HttpResponse(200, """{"choices":[{"message":{"content":"ready"}}]}""")
        }
        val r = ConnectionWizard(t).connect("gsk_test", "c1") as ConnectResult.Success
        eq("Connection successful. Ciel is ready.", r.message)
        eq(listOf("c1:fast", "c1:strong", "c1:vision"), r.configs.map { it.id })
        eq(CapabilityTier.ECONOMY, r.configs[0].tier); eq(CapabilityTier.ADVANCED, r.configs[1].tier)
        check(r.configs[2].supportsVision)
        check(sent.all { it.startsWith("https://api.groq.com/") }) { "key sent to another host: $sent" }
    }
    T.test("connect: falls back to the next model when the first can't answer") {
        val t = FakeTransport { req ->
            when {
                req.url.endsWith("/models") -> HttpResponse(200, groqModels)
                req.body!!.contains("llama-3.1-8b-instant") -> HttpResponse(403, """{"error":{"message":"model not enabled"}}""")
                else -> HttpResponse(200, """{"choices":[{"message":{"content":"ready"}}]}""")
            }
        }
        val r = ConnectionWizard(t).connect("gsk_test", "c2") as ConnectResult.Success
        check(r.configs.first().model != "llama-3.1-8b-instant")
    }
    T.test("connect: rejected key and unknown key give plain-language results") {
        val bad = ConnectionWizard(FakeTransport { HttpResponse(401, """{"error":{"message":"Invalid API Key"}}""") }).connect("gsk_wrong", "c3")
        check(bad is ConnectResult.Failure && "rejected" in bad.message) { bad.toString() }
        check(ConnectionWizard(FakeTransport { error("must not send an unidentified key anywhere") }).connect("plainkey123", "c4") is ConnectResult.NeedsProvider)
        val custom = ConnectionWizard(FakeTransport { HttpResponse(200, "{}") }).connect("", "c5", presetKey = "custom")
        check(custom is ConnectResult.Failure && "address" in custom.message)
    }
}
