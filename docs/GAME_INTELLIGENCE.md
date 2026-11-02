# Game Intelligence (v0.4)

How Ciel understands, explores and plays permitted single-player games, with
Cookie Run: Kingdom (`com.devsisters.ck`) as the first dedicated profile.

## Why Ciel struggled with Cookie Run: Kingdom (diagnosis)

| Cause | Effect | Fix |
|---|---|---|
| The game draws everything itself; Android's accessibility tree is empty. | Ciel had no buttons or text to read. | On-device text recognition (ML Kit, offline) turns on-screen text into "seen" elements with positions. |
| "Send screenshots to vision models" was off by default, deep in Privacy settings. | With an empty tree and no image, the planner was blind. | Clear switches in **Settings → Games → How Ciel sees games**; tasks stop at once with an explanation if Ciel cannot see. |
| Change detection compared accessibility elements only. | Every tap in the game looked like "no visible change", so Ciel refused to repeat it and gave up as "stuck". | 64-bit image fingerprint per observation; a tap counts as effective when the image changed. |
| Screenshots were downscaled but taps were in device pixels. | Coordinates read from the image landed in the wrong place. | `tap_pct` — positions as fractions of the image, converted on the phone. |
| One long "do the objective" run. | No plan, no step progress, no partial results. | Objective planner: prioritised steps, each proven on screen, honest report. |

## Components (game-engine module unless noted)

| Brief component | Implementation |
|---|---|
| Game Intelligence Manager | `GameEngine.runObjective`, `GameProfile.knowledge`, `GameCatalog` |
| Screen Understanding Engine | `GameScreenReader` (states, elements, timers, counters, confidence), `ScreenStateTracker` (multiple observations); Android: `ScreenVision` (OCR, hash) |
| Game Exploration Engine | `ObjectivePlanner` EXPLORE plan: survey, then one system at a time, read-only, least-known first |
| Quest / Kingdom / Reward engines | Plan steps (inspect quests, claim, easy objectives, collect production, inspect buildings, upgrade, produce) |
| Objective Planner | `ObjectivePlanner.intentOf` / `plan` |
| Game Action Executor | `ScreenAgent.session` + `GamePlannerDecorator` (all actions still go through `GuardedDevice`) |
| Task Verification Engine | Steps finish only with on-screen evidence; every action records target, expectation and observed change |
| Game Knowledge Database | `GameKnowledgeBase` stored in the profile (searchable, confidence, sources, re-validation) |
| Floating Activity Controller | app: `overlay/ActivityNotch.kt`, fed by `GameEngine.activity` |
| Non-Blocking Communication Manager | `TaskChannel`, `TaskMessage`, `GameEngine.answer`, app `AppGraph.messageGameTask` |

## Knowledge

* Every entry has a **system**, **kind** (location, action, mechanic, risk, workflow, unresolved),
  **confidence** (low/medium/high), **source** (observed, user, imported), the game version, how
  often it was seen or failed, and when it was last verified.
* Locations are stored **semantically**: "On kingdom screen: tap \"Quests\"", plus landmarks
  (texts on the destination). A position is only a hint and is never trusted on its own.
* Seeing a fact again raises confidence; a route that did nothing lowers it and marks it for
  re-checking; a game update marks everything for re-checking and lowers confidence one level.
* Account secrets (passwords, tokens, e-mail, codes) are never stored.
* Shared/imported knowledge is capped at medium confidence and must be re-observed; spending rules
  and automation permission are never imported.
* View, search, forget one item or clear everything in **Settings → Games → What Ciel learned**.

The Cookie Run: Kingdom profile starts with recognition words (e.g. Crystals and Rainbow Cubes as
premium currencies) and safety rules (Kingdom Arena is competitive PvP and is not played). It
contains no screen positions: those are learned by exploring.

## Spending rules (Settings → Games → Spending rules)

* Premium currency: never (default) or ask every time.
* Upgrades: never · ask before every upgrade (default) · only listed buildings · automatically when affordable.
* Production: ask or automatic (default automatic). Free rewards: claim automatically (default) or ask.
* Resource reserve percentage.
* **Real-money purchases always need explicit approval** (twice: the game rules and the global action policy).

## Talking to Ciel while she plays

| You say / type | What happens |
|---|---|
| "What are you doing?", "Why are you upgrading that?" | Answered from the live task (screen reading, recent verified actions, plan). The task keeps running. |
| An upgrade that needs your decision | Only that action waits. The question appears in the notch; Ciel continues with other safe work. |
| Premium currency (if allowed to ask) / real money | Explicit approval dialog before anything happens. |
| "Skip that building", "Only collect rewards" | Added as an instruction the planner reads before every decision. |
| "Pause" / "Continue" | Pauses at the next step boundary / resumes. |
| "Stop" or the notch's Stop | Emergency stop, immediately, never waiting for an AI reply. |

Voice goes to Ciel, never to the game: speech is handled by Ciel's recogniser and routed to the task.

## Limits (honest)

* **Not tested against the real game.** Everything above is unit-tested with simulated screens
  (66 tests), but the game's actual menus, text and timings have not been verified on a phone.
* OCR reads Latin-script text only, and stylised game fonts may be misread (those elements are
  marked medium confidence). Icons without text need screenshots + a vision model.
* Android allows about one screenshot per second; each step therefore takes 2–4 seconds.
* The notch appears in screenshots; the model is told to ignore it and Ciel's taps pass through it.
* Battles and anything requiring fast reactions are out of scope.
* Game terms may forbid automation: review them before allowing a game.
