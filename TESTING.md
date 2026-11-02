# Testing

## Automated (pure-Kotlin core) — 45 tests

```bash
./gradlew :testing:run                       # with Gradle
KOTLINC_HOME=/path/to/kotlinc ./scripts/verify-core.sh   # with only a Kotlin compiler
```

Coverage by module:

| Module | What is verified |
|---|---|
| core | JSON parse/write/round-trip and errors; secret redaction; real HTTP streaming against a local server; offline error classification |
| ai-providers | OpenAI-compatible request mapping (tools, images, tool results), tool-call parsing, SSE streaming with fragmented tool arguments, 401 message without key leakage, free-tier detection only from real pricing data, custom auth headers; Gemini system instruction, role merging, functionCall/functionResponse, SSE streaming, blocked prompts, model list |
| task-engine | Risk policy (purchases incl. coordinate taps, sending, deleting, CAPTCHA), declined confirmation never reaches the device, **emergency stop cancels running work and blocks later actions**, screen agent completes a multi-screen task with evidence, refuses repeated ineffective actions, screen lock serialisation |
| skill-system | Calculator, app-name resolution (no wild guesses), missing-permission refusal, JSON skill validation and guarded execution |
| memory-engine | Sensitive-data rejection, de-duplication, relevance search, retention settings, expiry |
| communication | Never guesses ambiguous recipients/numbers; nothing sent without approval; group warning; honest result wording |
| game-engine | Unreviewed games never automated; update detection; verified-run learning clears the flag; profile export/import resets policy |
| research-engine | HTML extraction, private-address refusal, real HTTP failure reporting, untrusted wrapping, offline failure |
| core-ai | Offline intents, complexity classification, provider selection rules, prompt-injection guard, planner JSON parsing, end-to-end engine: tool round-trip, provider fallback, total failure, stop command, offline path, memory injection |

## Device test checklist (manual, required before release)

Use a real phone (Android 10+ recommended) with the debug APK.

**Setup**
- [ ] App installs, icon appears, opens in portrait and landscape.
- [ ] Add a Gemini or Groq provider → Test shows ✓ with latency.
- [ ] Wrong key → Test shows "Authentication failed" without the key.

**Conversation**
- [ ] Simple question → short answer; "analyse…" → structured answer; streaming visible.
- [ ] Airplane mode → "calculate 2^10" works offline; open-ended question explains offline limits.
- [ ] Kill and reopen app → history preserved.

**Voice**
- [ ] Mic button → speech recognised → reply spoken in chosen voice; tap orb while speaking interrupts.
- [ ] Set Ciel as default assistant → assistant gesture opens Ciel listening.
- [ ] Quick Settings tile "Ask Ciel" works.
- [ ] Wake word: download model, enable, say "Ciel" from home screen; say "Ciel stop" during a task.

**Screen control & stop**
- [ ] Enable accessibility service; "Ciel, open Settings and turn on dark theme" completes and reports verified.
- [ ] During a task: STOP pill visible; tapping it halts instantly; task shows STOPPED in history.
- [ ] Double-tap floating orb halts; accessibility button halts; notification action halts.
- [ ] Ask Ciel to buy something in a store app → confirmation dialog appears; Decline → nothing happens.

**Messaging**
- [ ] "Message <ambiguous name>…" → Ciel asks which contact.
- [ ] SMS to yourself → approval card shows exact text → "accepted for delivery".
- [ ] WhatsApp → opens chat with text; reply wording says not yet sent.

**Games**
- [ ] Open a single-player game; "what game is this?" creates a profile.
- [ ] Automation refused until you set the policy in Games.
- [ ] After allowing: "collect my daily rewards" runs, stops at any purchase prompt.

**Memory & privacy**
- [ ] "Remember that I prefer metric units" → appears in Memory; edit/delete works; "my password is…" is refused.
- [ ] Delete all API keys → providers show key missing.
