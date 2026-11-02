# Development log

## Milestone 1 — 2026-11-02

### Environment
The development sandbox could reach GitHub but **not** Google Maven, Maven Central or the Gradle plugin portal, and had no Android SDK. Consequences:
- The nine pure-Kotlin modules were compiled with the Kotlin 2.2 compiler (downloaded from GitHub releases) and tested with the bundled kotlinx-coroutines — **45/45 tests pass**.
- The Android `app` module was written against Android API 35 / AndroidX / Compose but **has not yet been compiled**. The first GitHub Actions run (or Android Studio sync) is the first compilation; expect a small number of compile fixes. Gradle build files have likewise not been executed.
- No APK has been produced yet. CI is configured to produce the debug APK automatically.

### Implemented and tested (automated, JVM)
- JSON, HTTP transport (incl. real local-server streaming), secret redaction.
- OpenAI-compatible and Gemini adapters: request/response mapping, streaming, tool calls, images, model lists, error messages.
- Provider selection (tiers, vision, context, daily limits, 429 cooldown, override) and gateway fallback.
- Ciel engine end-to-end with scripted providers: tool loop, verification flags, fallback, failures, stop, offline mode, memory injection.
- Emergency stop semantics, risk policy, confirmation gate, screen agent (completion with evidence, refusal to repeat ineffective actions), resource lock.
- Skills: calculator, app resolution, permission gating, JSON skills.
- Memory policy/search; communication approval and recipient resolution; game policy gate, learning, update detection; research extraction and safety.

### Implemented, not yet compiled or device-tested (Android)
Compose UI (Home/Mode A with magic circle and system notices, Tasks, System dashboard, Settings, Providers, Memory, Permissions, Games, Skills, confirmation dialog), Keystore vault, Room + DataStore, accessibility controller with STOP pill and accessibility-button stop, floating Ciel (Mode B), assistant entry + Quick Settings tile, speech input, TTS voice selection, Vosk wake-word service, reminders (AlarmManager + boot re-arm), documents (MediaStore), contacts, SMS/WhatsApp/Telegram/email/Discord senders, notification listener.

### Not yet implemented / known gaps
- **On-device language model** for offline reasoning (Phase 6). Offline mode currently = deterministic local commands + all offline skills. Candidate: an OpenAI-compatible local server (e.g. on a LAN PC) already works as a "custom" provider; true on-phone inference (MediaPipe LLM / llama.cpp) is a future module.
- **Voice barge-in** (interrupting by speaking): interruption is by tapping the orb, the floating card, or "Ciel, stop" via the wake word.
- **Usage counters** for daily limits are in memory and reset when the app restarts (persist in DataStore next).
- **Gmail API**, direct Telegram sending (not possible for personal accounts without a user-client library), Discord DMs (not authorised).
- **Game visual understanding** relies on the accessibility tree plus screenshots sent to a vision model; no on-device vision model yet.
- Splitting `app` into Android library modules (voice, overlay, accessibility…) — packages are already separated.
- Battery measurement, lock-screen behaviour, background speech recognition after the wake word, and per-OEM behaviour need real-device testing.
- Release signing requires your keystore (see BUILDING.md).

### External configuration required
- At least one AI provider key (Gemini, Groq, OpenRouter, OpenAI) or a local OpenAI-compatible server.
- Optional: Brave/Tavily key or SearXNG instance for research; Discord webhooks; the Vosk model download for the wake word.

### How to run the latest build
Push to GitHub → Actions → download `ciel-debug-apk` → install. Or `./gradlew :app:assembleDebug` in Android Studio. Then follow TESTING.md's device checklist.

### Next milestone plan
1. Fix any compile issues from the first CI run; run the device checklist on a physical phone.
2. Persist usage counters; add per-provider usage view.
3. Voice barge-in and lock-screen flow.
4. On-device model module for offline reasoning.
5. Game engine: test on a single-player game that permits automation; tune settle timings and screen signatures.
6. Split Android packages into library modules; add instrumented tests for KeyVault and Room.
