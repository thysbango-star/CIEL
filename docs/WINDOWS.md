# Ciel for Windows

The same Ciel — persona, AI providers, memory, safety rules and research — as a Windows 10/11 desktop app,
plus tools for your PC. Built with Compose for Desktop on the shared Ciel core.

## Install
- **Installer:** https://github.com/thysbango-star/CIEL/releases/download/desktop-latest/ciel-windows.msi
  (installs for your user only; no administrator rights needed).
- **Portable:** https://github.com/thysbango-star/CIEL/releases/download/desktop-latest/ciel-windows-portable.zip — unzip, run `Ciel.exe`.
- The test build is not code-signed, so SmartScreen may warn: **More info → Run anyway**.

## Connect an AI
Connect → **Ollama** → address `http://localhost:11434` → Connect. (Ollama must be running: Start menu → Ollama.)
Any other provider works too (Gemini, OpenAI, OpenRouter, Groq, NVIDIA, custom). Keys are encrypted with
Windows DPAPI for your account, stored in `%APPDATA%\Ciel\keys.json`, and sent only to their provider.
The PC tools (Storage, Gaming, PC, Downloads) work without any AI.

## What it does
| Area | What Ciel does | What she never does |
|---|---|---|
| Storage | Drive overview; scans by category; largest files/folders; duplicates (verified by SHA-256); old downloads & installers; scan history; exclusions; open location | Upload file names/contents to an AI for analysis |
| Clean-up | Low risk / Review / Protected; you tick items and approve; personal files go to the Recycle Bin; temp/cache deleted only if older than a day; every action logged | Touch Windows, drivers, programs, saved games, browser profiles, password databases, the registry |
| Gaming Mode | Balanced / Maximum / Custom presets, game profiles (Minecraft included), plan you can untick, original state saved first, graceful closes verified, power plan switch, full restore (even after a restart) | Force-close apps with unsaved work; disable Defender, firewall, audio, network, drivers; change BIOS, overclocking or registry |
| Monitor | CPU, RAM, GPU %, GPU memory, NVIDIA temperature, disk — live overlay | Show numbers Windows can't measure |
| PC health | Low disk, memory pressure, startup apps, battery wear, power plan, last update, drivers, recent app errors | Install drivers or BIOS updates |
| Downloads | HTTPS only, real file name/size/type, publisher check, approval, SHA-256 compare, signature check, Defender scan, "from the internet" mark | Run or install anything; bypass UAC or SmartScreen |
| Autonomy | Assist (always asks) · Assisted (temp/cache clean-ups without asking) · Authorised (also Gaming Mode for profiles you mark) | Skip the question for personal files, programs or security |

Activity log: Tasks screen (also `%APPDATA%\Ciel\activity.jsonl`). Emergency stop: the ■ button, the floating assistant or the tray icon.

## Not yet on Windows
Voice input / "Hey Ciel", live sync with the phone (use Memory → Export / Import for now), reminders and scheduled tasks.

## For developers
- `windows-integration/` — PC logic (pure JVM, tested with fakes in `:testing:run`).
- `./gradlew :testing:windowsSmoke` — runs the real features on Windows (CI: `windows-latest`), including a power-plan round trip that must end on the original plan.
- `./gradlew :desktop:run` — start the app; `:desktop:packageMsi` — installer.
