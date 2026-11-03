# Ciel

A personal intelligence for Android, inspired by Ciel from *That Time I Got Reincarnated as a Slime*: calm, analytical, quietly proud of her work — and honest about what she has actually done.

> Fan-inspired, unofficial project. All visuals are original (no screenshots, footage, music or character art from the series). The default voice is your phone's own text-to-speech engine; no actor's voice is cloned.

## What Ciel does (milestone 1)

| Area | Status |
|---|---|
| Conversation with Ciel's personality, streaming replies, ten visual states | Implemented |
| Bring-your-own-key providers: OpenAI-compatible (OpenAI, OpenRouter, Groq, Ollama/LM Studio/vLLM…), Google Gemini (native) | Implemented, adapters unit-tested |
| Automatic model selection by task, fallback on rate limits/outages, manual override | Implemented, tested |
| Keys encrypted with Android Keystore (AES-256-GCM), secure deletion | Implemented |
| Tool-using intelligence engine with verified-result reporting | Implemented, tested |
| Memory: six categories, search, edit, delete, disable, sensitive-data filter | Implemented, tested |
| Screen control via Accessibility (read, tap, type, scroll, gestures) | Implemented |
| Autonomous multi-step tasks (observe → plan → act → verify loop) | Implemented, loop tested with a simulated phone |
| Emergency stop: voice, in-app, floating STOP pill, double-tap orb, accessibility button, notification | Implemented, stop semantics tested |
| Universal Game Adaptation Engine: profiles, recognition, learning, update detection, policy gate | Implemented, tested with simulated game |
| Messaging: SMS (direct), WhatsApp/Telegram/Email (hand-off), Discord (webhooks), notification summaries | Implemented; approval workflow tested |
| Research: Brave / Tavily / SearXNG search, page reading, source tiers, citations | Implemented, tested |
| Voice: speech recognition, Ciel's voice (selectable), wake word "Ciel" (offline, Vosk) | Implemented (device testing required) |
| Activation: default assistant gesture, Quick Settings tile, floating Ciel, wake word | Implemented |
| Offline mode: local commands without any AI model | Implemented, tested |
| Installable JSON skills without rebuilding | Implemented, tested |

See **[DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md)** for exactly what was tested where, and what remains.

## Quick start (v0.2)

**Windows:** https://github.com/thysbango-star/CIEL/releases/download/desktop-latest/ciel-windows.msi — see [docs/WINDOWS.md](docs/WINDOWS.md).

Download the latest APK: https://github.com/thysbango-star/CIEL/releases/download/debug-latest/ciel-debug.apk — on first launch Ciel walks you through choosing a design, connecting an AI (paste a key) and turning on **Hey Ciel**.

## Quick start (details)

1. Get the APK: download `ciel-debug-apk` from the latest GitHub Actions run, or build it (see [BUILDING.md](BUILDING.md)).
2. Install it on your phone (allow "install unknown apps" for your browser/file manager).
3. Open Ciel → **Settings → AI providers → Add provider**. Paste a key (see [API_SETUP.md](API_SETUP.md) — Gemini, Groq and OpenRouter offer free access at the time of writing; check their sites).
4. Tap **Test**. Then talk to Ciel.
5. Optional, as you need them (**System → Permissions**): screen control, floating Ciel, notifications, contacts, SMS.
6. Optional: Android Settings → Apps → Default apps → **Digital assistant app → Ciel**, so the assistant gesture summons her from anywhere.

## Safety model

- The language model never controls Android directly. It requests tools; every device action passes through one guarded path: **emergency stop check → risk policy → your confirmation → execution → audit log**.
- Purchases, sending, deleting, security and account changes always need your explicit approval. CAPTCHAs and human-verification screens stop automation.
- Text from web pages, screens, notifications and documents is wrapped as untrusted data and cannot issue instructions.
- Ciel reports "started", "submitted" and "verified" precisely — she never claims success a tool did not confirm.

## Repository layout

```
core/             JSON, HTTP transport, secret redaction            (pure Kotlin)
ai-providers/     Provider interface + OpenAI-compatible & Gemini adapters
task-engine/      Tasks, emergency stop, risk policy, guarded device, screen agent
skill-system/     Skill interface, registry, JSON skills, built-in skills
memory-engine/    Memory model, policy, search, Memory skill
research-engine/  Search providers, page fetcher, Research skill
communication/    Messaging workflow, recipient resolution, Communication skill
game-engine/      Game profiles, recognition, learning, Game Master skill
core-ai/          Persona, provider selection, LLM gateway, planner, CielEngine
testing/          Test suite for all pure-Kotlin modules
app/              Android app: Compose UI, Keystore, Room, voice, accessibility, overlay
```

Docs: [ARCHITECTURE](ARCHITECTURE.md) · [BUILDING](BUILDING.md) · [API_SETUP](API_SETUP.md) · [PERMISSIONS](PERMISSIONS.md) · [TESTING](TESTING.md) · [CHANGELOG](CHANGELOG.md) · [CONTRIBUTING](CONTRIBUTING.md) · [Game Intelligence](docs/GAME_INTELLIGENCE.md) · [UI guidelines](docs/UI_GUIDELINES.md) · [Ollama & NVIDIA](docs/OLLAMA_AND_NVIDIA.md)
