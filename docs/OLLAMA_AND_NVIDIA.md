# Ollama, NVIDIA and where your AI runs (v0.5)

## Why Ciel crashed when connecting to Ollama
1. Android blocks plain `http://` connections by default (apps targeting Android 9+). Ollama
   serves plain HTTP, so the request failed with an error Ciel's network layer did not classify.
2. That unclassified error (also "no route to host" when the PC isn't reachable) escaped the
   Connect button's coroutine and crashed the app.

Fixed: every network failure is now classified into a readable message, the Connect flow catches
everything, and plain HTTP is allowed **only to addresses on your own network** (192.168.x.x,
10.x, 172.16–31.x, `.local`, Tailscale's 100.64/10). Ciel refuses plain HTTP to internet hosts.

Also fixed: many Ollama models reject requests that include tools ("does not support tools",
HTTP 400). Ciel now reads each model's capabilities from Ollama (`/api/show`) and sends tools
only to models that support them.

## Connect Ollama
1. On the PC, make Ollama listen on the network: set the environment variable
   `OLLAMA_HOST=0.0.0.0` and restart Ollama. Allow TCP port 11434 in the PC's firewall.
2. Keep the phone on the same Wi-Fi.
3. Ciel → Connect → **Ollama (my own AI)** → **Find Ollama on my Wi-Fi** (or type the PC's address) → Connect.

Ciel lists installed models and picks a fast model (prefers one already loaded), a larger
reasoning model and an image model if installed. The first answer can take a minute while the
model loads. If a model needs more memory than the PC has, the test fails with that message.
Ciel does not download models; install them on the PC with `ollama pull <model>` (sizes are
shown on ollama.com).

**Where it runs:** Ollama on a PC runs *on your network*, not on the phone. Ollama inside Termux
on the phone (127.0.0.1) runs *on this phone*. Each connection shows this.

## NVIDIA
* **NVIDIA API catalog** (build.nvidia.com): paste an `nvapi-…` key; Ciel recognises it,
  lists models, skips embedding/retrieval/image-generation models, and tests chat models.
* **Self-hosted NIM**: choose "NVIDIA NIM (self-hosted)" and enter the container's address
  (OpenAI-compatible API, default port 8000).

## When the AI is unavailable during a game task
Routine game decisions are made on the phone. If the AI model fails (credits, limits, offline),
Ciel tells you once, switches to local-only for the rest of the task and continues with what she
can do (claiming, collecting, known routes, reading screens). Steps that need judgement on
unfamiliar screens stop with a clear reason. The report shows how many decisions were made on the
phone and how many by the AI — measured, not estimated.

## Limits
* Not tested with a real Ollama server or NVIDIA account from a phone; tested against a local
  Ollama-compatible test server (model list, capabilities, chat) and recorded NVIDIA model ids.
* Wi-Fi discovery scans the phone's own /24 subnet; other network layouts need the address typed.
* Remaining credits are not shown: these providers don't report them through this API.
