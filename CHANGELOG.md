# Changelog

## 0.5.0 — 2026-11-03

### Fixed
- **Crash when connecting to Ollama** (Android's plain-HTTP block and unclassified network errors). Plain HTTP is allowed only to your own network.
- Ollama models without tool support no longer fail with HTTP 400 (capabilities read from Ollama).

### Added
- **Ollama**: find it on your Wi-Fi, installed models with capabilities, automatic fast/reasoning/vision choice, connection test that allows for model loading.
- **NVIDIA** API catalog (nvapi- keys) and **self-hosted NIM**. Each connection shows where it runs (phone / your network / cloud).
- **Local-first game play**: routine decisions on the phone; the AI only for unfamiliar screens; automatic local-only fallback when the AI fails; measured decision counts.
- **Gameplay manager** (Settings → Games → Open gameplay manager): task library by category, queue with drag-and-drop, stop rules (once, runs, until stamina/daily attempts), presets, locked order, event-first ordering, event deadlines read from the game with warnings.
- Activity notch: queue (✓ ● ○), next action, Skip task; live tap markers showing where Ciel touched.

### Tests
- 78 core tests (12 new: Ollama connect/discovery, crash regression, NVIDIA filtering, queue ordering, deadlines, local-only runs, stop rules).

## 0.4.0 — 2026-11-02

### Fixed
- **Keyboard**: the chat input stays directly above the keyboard on every device (insets applied once; bottom bar hides while typing). The input grows with the text, then scrolls inside; the keyboard's Send key sends; unsent text is kept. Chat stays on the newest message unless you scroll up ("latest" button).
- **Theme selector**: all 8 themes are complete cards in an adaptive grid (name, live preview, colours, description, selected badge, Preview, Apply). Full-screen preview (Home / Chat / Floating / Voice). Previews no longer shift or clip.
- Rows of chips/buttons wrap on small screens; dialogs fit the screen with the keyboard open; Floating Ciel stays on screen.
- Animations loop seamlessly: no jumps on state changes or after long running; effects fade in and out. Smooth screen transitions, crystal resizing, status cross-fades, mic halo.

### Added — Game Intelligence (see docs/GAME_INTELLIGENCE.md)
- Cookie Run: Kingdom profile; objective planner (daily quests, collect rewards, check/upgrade buildings, production, improve kingdom, explore & learn).
- Screen understanding with confidence (on-device text reading, image fingerprints, several observations), recovery from loading / connection errors, honest stops for login, maintenance, updates and human checks.
- Learned knowledge base with confidence, routes, landmarks, re-checking after game updates; view, search, forget and clear it.
- Spending rules: premium currency, upgrades, production, auto-claim, reserve. Real money always asks.
- **Floating activity notch** above the game: current action, real step progress, status; Pause/Resume, Stop, Ask Ciel, View task.
- Ask questions while Ciel plays without stopping her; clarifications pause only the affected action.

### Tests
- 66 core tests (11 new for game intelligence).

## 0.3.0 — 2026-11-02

### Added
- **Ciel's crystal**: the supplied glowing core image (made transparent, tinted per theme) inside orbital rings. Distinct idle, listening (ripples + voice wave), thinking (fast rings + symbols), analysing (data orbit), executing (energy trail + progress arc), speaking (pulse), error and offline (dimmed) states. Also used by Floating Ciel and the Hey Ciel panel.
- **8 themes**: Azure Calm (default), Ethereal Light, Void Minimal, Twilight Sage, Nature's Wisdom, Divine Light, Starfall, Glassmorphism — palette, background, particles and crystal tint. Controls for glow, particles, ring speed, panel transparency, animation level and floating size. The 4 layouts remain; Great Sage (default) rebuilt after the reference sheet.
- **Hey Ciel reliability**: model loads off the main thread; health shows only what's real; automatic recovery from microphone errors; sensitivity (Low/Normal/High); "Ciel" alone on/off; **Test Hey Ciel** diagnostic (permission, notifications, model, battery optimisation, service, microphone signal, live wake-word test showing what was heard).
- **Tasks tab**: new task with app/game picker, templates, queue (Queued state), pause/resume, cancel, run again, plan and result details, history filters, action log, autonomy limits (max steps, time limit), finish notifications.
- **Voice commands for Ciel herself**: "open tasks / chats / memory / skills / connections", "go home", "switch to Twilight Sage", "switch to Raphael layout", "stop talking", "repeat that", "what are you doing?".
- **Memory export / import** (JSON; imports re-check the sensitive-data filter).

### Optimised
- Theme backgrounds are drawn once and cached; the floating crystal redraws ~15×/s when idle; sliders save when released; voice model loading no longer blocks the UI.

### Tests
- 55 core tests (new: pause/resume, task queue, memory backup).

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
