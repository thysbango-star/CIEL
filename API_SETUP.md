# API setup

Ciel uses **your own keys**. Keys are typed into the app (Settings → AI providers), encrypted with the Android Keystore, and sent only to the provider they belong to. They are never in source code, logs, backups or this repository.

Free tiers, model names and limits change often. Ciel does not hard-code them: use **List** in the provider editor to load the provider's current models, and check the provider's site for current terms. Pricing/free-tier status is shown as *unknown* unless the provider publishes it through its API (OpenRouter does) or you set it.

## AI providers

| Provider | Base URL (preset) | Protocol | Get a key | Notes |
|---|---|---|---|---|
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta` | Gemini native | aistudio.google.com/apikey | Header `x-goog-api-key`. Vision and tool calling supported by current Gemini models. |
| OpenRouter | `https://openrouter.ai/api/v1` | OpenAI-compatible | openrouter.ai/keys | Model list includes pricing; models with zero price are marked free automatically. Many models; `openrouter/auto` routes automatically. |
| Groq | `https://api.groq.com/openai/v1` | OpenAI-compatible | console.groq.com/keys | Very fast inference; check your account's rate limits in the console. |
| OpenAI | `https://api.openai.com/v1` | OpenAI-compatible | platform.openai.com/api-keys | Paid. |
| Custom / local | e.g. `http://192.168.1.10:11434/v1` | OpenAI-compatible | — | Ollama, LM Studio, vLLM, LiteLLM… Set auth to *none* or a custom header. A server on your LAN keeps everything private. |

### Recommended setup for automatic selection

Add two or three models and set their **tier**:
- *economy* — a fast, cheap/free model for chat (e.g. a small Groq or Gemini Flash-class model),
- *advanced* — your most capable model for analysis/research/automation,
- mark at least one model **Supports images** for photo questions and game automation with screenshots.

Ciel picks by task complexity, falls back to the next model on rate limits or outages (cooling the failed one down for a minute), and respects optional **daily request limits** you set.

### Data handling

Everything you send to Ciel — and, during screen automation, the on-screen element list (and screenshots if enabled) — is processed by the selected provider under **its** data policy. The editor requires you to acknowledge this per provider. For maximum privacy, use a local model.

## Web search (Research skill)

| Provider | Setup |
|---|---|
| Brave Search API | Create a key at api.search.brave.com; Settings → Research → Brave → paste key. |
| Tavily | Key from tavily.com; Settings → Research → Tavily. |
| SearXNG | Your own instance with `json` added to `search.formats` in `settings.yml`; Settings → Research → SearXNG → base URL. |

Without a search provider, Ciel can still open pages you give her and can open a browser search for you.

## Messaging

| Platform | Method | Sends by itself? |
|---|---|---|
| SMS | Android `SmsManager` (SEND_SMS permission) | Yes, after your approval; reports when the network accepted it |
| WhatsApp | Official click-to-chat link (`wa.me`) | No — opens the chat with text filled in; you tap send (no public API exists for personal accounts) |
| Telegram | Share intent to the Telegram app | No — you choose the chat and tap send |
| Email | `mailto:` intent (Gmail when installed) | No — you tap send |
| Discord | Channel webhooks you create (Server settings → Integrations → Webhooks) | Yes, to that channel, after your approval. DMs are not supported (no authorised method). |

**Gmail API (future):** sending silently through Gmail requires a Google Cloud project, an OAuth client of type Android with your signing-certificate SHA-1, and the `gmail.send` scope. This is planned as a separate integration; it is not in milestone 1.

## Wake word model

The offline wake word uses the Vosk small English model (~40 MB, Apache-2.0) downloaded from `alphacephei.com` when you tap **Download** in Settings → Activation. Audio for wake-word detection never leaves the phone.
