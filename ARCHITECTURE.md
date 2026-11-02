# Architecture

## Principles

1. **One intelligence.** Every feature is a *skill* offered to the same `CielEngine`. Voice, the full-screen UI, the floating orb and the wake word all feed the same conversation.
2. **The model proposes, the system disposes.** Models return tool calls or a single JSON action; Kotlin code validates and executes them through `GuardedDevice`.
3. **Honest state.** Tool results carry `success` and `verified`. Tasks only reach `COMPLETED` + `verified` when checked against the device. Messaging distinguishes *handed to app*, *submitted* and *delivered*.
4. **Platform-independent core.** Everything except Android glue is pure Kotlin (JVM) and unit-tested without a device.

## Module graph

```
core ──► ai-providers ──┐
  └────► task-engine ───┴─► skill-system ─┬─► memory-engine ─┐
                                          ├─► research-engine┤
                                          ├─► communication ─┼─► core-ai ─► app
                                          └─► game-engine ───┘
```

The spec's suggested `voice-engine`, `accessibility-controller`, `android-integration`, `ui-components`, `settings` and `security` modules live as packages inside `app` (`voice/`, `platform/`, `ui/`, `data/`, `security/`) because each depends on the Android SDK and they share one process-wide graph. They can be split into Android library modules later without changing the core.

## Request flow

```
user text / speech
   │
   ▼
CielEngine.respond()
   ├─ "stop"? ──────────────► EmergencyStop.trigger()   (never reaches a model)
   ├─ offline / no provider ─► LocalIntentParser → SkillRegistry (calculate, open_app, reminders, memory)
   └─ online:
        ComplexityClassifier → ProviderSelector (tier, vision, context, limits, cooldowns, free tier, preference)
        CielPersona.systemPrompt (+ memories as background facts, + active tasks)
        loop ≤ maxToolRounds:
            LlmGateway.call(ranked providers, fallback on failure, streaming)
            tool calls → SkillRegistry.dispatch → ToolOutcome
                 untrusted content wrapped by PromptGuard
        Final answer
```

## Safety core (`task-engine`)

- **EmergencyStop** — a generation counter + job registry. `trigger()` cancels every registered coroutine and bumps the generation; `GuardedDevice` refuses any action from an older generation, so even a coroutine that ignores cancellation cannot touch the screen again. Listeners silence TTS, cancel listening and decline pending confirmations.
- **ActionPolicy** — classifies each action against the current screen: purchases (incl. currency/price patterns and coordinate taps near store buttons), sending, deleting and security/account changes are `CONSEQUENTIAL`; human-verification screens are `FORBIDDEN`.
- **GuardedDevice** — the only route to `DeviceController`. Stop check → policy → `ConfirmationGate` → perform → `ActionRecord` audit (stored in Room, visible in Tasks → Action log).
- **ResourceLocks** — the SCREEN lock serialises tasks that drive the UI; research or analysis tasks run in parallel.
- **ScreenAgent** — observe → `ActionPlanner.next()` → act → settle → re-observe. Tracks (screen signature, action) pairs that had no effect and refuses to repeat them; gives up honestly after repeated blocks or the step limit. Completion is accepted only from a fresh observation with stated evidence.

## Providers (`ai-providers`)

`AiProvider` (chat, stream, listModels, testConnection) with separate adapters:

| Adapter | Used for | Differences handled |
|---|---|---|
| `OpenAiCompatibleProvider` | OpenAI, OpenRouter, Groq, any `/chat/completions` server | Bearer/custom header/none auth, extra headers, image parts as data URLs, streamed tool-call fragments by index, model-list fields that differ per vendor (`context_length`, `context_window`, `pricing`, `architecture.input_modalities`) |
| `GeminiProvider` | Google Gemini API | `systemInstruction`, `user`/`model` roles with merged turns, `inline_data` images, `functionCall`/`functionResponse` parts, SSE via `alt=sse`, `promptFeedback.blockReason` |

Free-tier status is `UNKNOWN` unless a provider's live pricing data says otherwise (OpenRouter) or the user sets it. Adding a provider with a new wire format = one new adapter + one `ProtocolKind`.

## Skills

```kotlin
interface Skill { id; displayName; description; tools: List<ToolSpec>; requiredCapabilities; worksOffline
                  suspend fun invoke(tool, args, ctx): ToolOutcome }
```

Built-in: Calculate, System, Execute, Manage, Create, Memory, Research, Communication, Game Master. *Analyse*, *Predict* and *Create* are also reasoning modes (`ReasoningMode`) selectable in the UI.

**Installable skills** (`DeclarativeSkill`): JSON files of parameterised, whitelisted steps (`open_app`, `tap_text`, `tap`, `swipe`, `type`, `scroll`, `back`, `home`, `open_url`, `wait`, `expect_text`). They run through `GuardedDevice`, so they inherit every confirmation and the emergency stop. Install from Skills → Install; see `examples/skills/`.

## Memory

Six categories (conversation, preferences, projects, applications, games, temporary tasks). `MemoryManager` enforces settings (enabled, retained categories, inferred saves), rejects sensitive data, de-duplicates, expires task memories, and ranks search by keyword overlap + recency. Memories reach the model as background facts only, and only when "share with providers" is on.

## Game Adaptation Engine

- Recognition: foreground package via accessibility; profile created on first sight; `PackageManager` category/version.
- Profiles: identity, known screen signatures, navigation/mechanics/quests/limitations, verified strategies with success/failure counts, version; export/import via `GameProfileCodec` (imports never carry an "allowed" automation decision).
- Policy gate: `UNREVIEWED` (default, no automation) → user chooses *allowed single-player*, *menus & rewards only*, or *prohibited*.
- Play: the shared `ScreenAgent` with game knowledge, screenshots (if allowed) and longer settle times; same purchase/verification safeguards.
- Learning: verified runs store the effective action sequence; failures increment counters; a version change sets `interfaceChangeSuspected` until a verified run succeeds on the new version.

## Android layer (`app`)

| Package | Contents |
|---|---|
| `security` | `KeyVault`: Android Keystore AES-256-GCM; ciphertext in app-private prefs; excluded from backup |
| `data` | Room (`messages`, `memories`, `tasks`, `action_log`, `game_profiles`, `reminders`), DataStore settings (one JSON document, no secrets) |
| `platform` | `CielAccessibilityService` (observe/act, STOP pill, accessibility-button stop), `AndroidDeviceController`, network monitor, capability checker, reminders (AlarmManager), documents (MediaStore), contacts, messaging senders, notification listener |
| `voice` | `SpeechInput` (SpeechRecognizer, on-device when offline), `CielVoice` (TTS), `WakeWordService` + `VoskModels` |
| `overlay` | `FloatingCielService` (draggable orb, tap/double-tap/long-press, confirmations), `StopActionReceiver` |
| `assistant` | `AssistActivity` (ACTION_ASSIST → default assistant), Quick Settings tile |
| `ui` | Compose: Home (Mode A), Tasks, System dashboard, Settings, Providers, Memory, Permissions, Games, Skills |

`AppGraph` wires one instance of everything per process.
