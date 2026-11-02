# Changelog

## 0.2.0 — 2026-11-02 — Redesign

### Added
- **Hey Ciel**: say "Hey Ciel" (or "Ciel") from any screen. One-tap setup (microphone permission, one-time 40 MB offline model, start). A chime plays and a Gemini-style voice panel appears over the current app; if Android's recogniser is blocked in the background, Ciel transcribes the command offline.
- **One-step connections**: choose a provider (or let Ciel detect it from the key), paste the key, press Connect. Ciel validates the key, discovers models, picks fast / reasoning / vision models and tests them.
- **Four designs** with distinct layouts: Ciel (anime), Raphael (structured system), Great Sage (minimal, light), Hybrid (recommended). Design picker with live home / voice / floating previews; accent colour, animation level and light/dark in Appearance. The floating orb and voice panel follow the design.
- New navigation: Home · Chats · Tasks · Connect · Settings. Multiple conversations. Settings hub; technical provider options moved behind Developer mode. First-run setup: design → connect → Hey Ciel.
- 7 new tests for provider detection and automatic setup (52 total).

## 0.1.0-milestone1 — 2026-11-02

First functional milestone covering phases 1–4 and the cores of phases 5–6.

### Added
- Modular project: 9 pure-Kotlin modules + Android app; GitHub Actions for tests, debug APK, lint, and signed release on tags.
- Intelligence engine with Ciel persona, reasoning modes (converse/analyse/predict/create/research), tool loop, honest verified reporting, prompt-injection framing.
- Provider system: OpenAI-compatible and Gemini adapters, provider manager UI, model listing, connection test, automatic selection with fallback and cooldowns, Keystore-encrypted keys.
- Task engine: global emergency stop with generation fencing, risk policy, confirmation broker, audit log, screen lock, autonomous screen agent.
- Accessibility screen control, floating Ciel overlay, STOP pill, accessibility-button stop, default-assistant entry, Quick Settings tile.
- Voice: speech recognition, TTS with voice selection, continuous conversation, offline wake word (Vosk).
- Memory with six categories, controls and sensitive-data filter.
- Game Adaptation Engine with profiles, policy gate, learning and update detection.
- Communication engine: SMS, WhatsApp/Telegram/email hand-off, Discord webhooks, notification summaries.
- Research: Brave/Tavily/SearXNG, page reading, source tiers.
- Installable JSON skills.
- 45 automated core tests.
