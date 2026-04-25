# Grandparent AI

Claude-powered Mobile Use Agent for Android. **The phone controls itself.**

A voice-driven assistant for elderly users that:
- Listens for what they want ("send a WhatsApp to my son", "is this call a scam?", "I fell, help")
- Sees the screen via Android's Accessibility Service (no laptop, no ADB)
- Drives the phone — taps, scrolls, types — using Claude as the brain
- Speaks results back via on-device TTS

This is computer use, but for mobile. Inspired by [minitap-ai/mobile-use](https://github.com/minitap-ai/mobile-use)'s multi-agent architecture (orchestrator-routes-to-specialist), but native Android instead of Python+ADB.

## Architecture

```
User speaks
   │
   ▼
SpeechRecognizer → text
   │
   ▼
Orchestrator (Claude Haiku, text-only)
   │
   ├── DIRECT          → speak answer back
   ├── SCAM_SHIELD     ┐
   ├── EMERGENCY       ├── AgentService.runAgentLoop(specialist prompt)
   └── WHATSAPP        ┘                │
                                        ▼
                       loop:
                         AccessibilityService.takeScreenshot()
                            │
                            ▼
                         Claude Sonnet 4.6 (vision) → "TAP: 540 1200" / "TYPE: …" / "DONE: …"
                            │
                            ▼
                         dispatchGesture() / ACTION_SET_TEXT / GLOBAL_ACTION_HOME
                            │
                            ▼
                         delay(~1s) → next screenshot
                                        │
                                        ▼
                                  TextToSpeech speaks final result
```

## Project layout

```
grandparent-ai/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/grandparentai/
│       │   ├── MainActivity.kt              # UI, voice in, TTS out
│       │   ├── AgentService.kt              # AccessibilityService — the loop body
│       │   ├── AgentForegroundService.kt    # Notification while loop runs
│       │   ├── AgentBridge.kt               # Singleton hand-off Activity ↔ Service
│       │   ├── ScreenCaptureManager.kt      # takeScreenshot() → base64 PNG
│       │   ├── ClaudeApiClient.kt           # Anthropic Messages API (vision + text)
│       │   ├── Orchestrator.kt              # Routes user intent to specialist
│       │   ├── Action.kt / ActionParser.kt  # Parse model output → atomic gestures
│       │   └── agents/AgentPrompts.kt       # ScamShield / Emergency / WhatsApp prompts
│       └── res/
│           ├── layout/activity_main.xml
│           ├── xml/accessibility_service_config.xml
│           └── values/{strings,colors,themes}.xml
├── settings.gradle.kts
└── build.gradle.kts
```

## Setup

### 1. Get an Anthropic API key

Grab one from https://console.anthropic.com.

### 2. Drop it into `local.properties`

```bash
cp local.properties.example local.properties
# edit local.properties:
#   ANTHROPIC_API_KEY=sk-ant-...
#   sdk.dir=<absolute path to your Android SDK>
```

`local.properties` is gitignored.

### 3. Build & install

Open in Android Studio, or:

```bash
./gradlew installDebug
```

Requires:
- Android Studio Hedgehog+ (AGP 8.5)
- JDK 17
- Android device on **API 30 (Android 11)** or higher (for `AccessibilityService.takeScreenshot()`)

### 4. Enable on the device

On first launch:
1. Tap **TAP TO TALK**.
2. The app asks for **Microphone** permission → allow.
3. The app prompts you to enable **Accessibility** → tap **Open settings** → find **Grandparent AI Agent** → toggle on.
4. Come back to the app and tap **TAP TO TALK** again.

## How to use

Tap the big blue button and say one of:

- *"Send a WhatsApp to my son saying I had lunch."*
- *"Someone is calling saying they're from the bank and need my PIN."*
- *"I fell, please get help."*
- *"What time is it?"*  ← routes to DIRECT, just answers via TTS

The bottom of the screen shows a live log of every step the agent takes.

## Notes & trade-offs

- **`AccessibilityService.takeScreenshot()` over MediaProjection** — the spec called for MediaProjection, but `takeScreenshot()` (added in API 30) needs no separate user-consent dialog every session, just the one Accessibility toggle. Cleaner UX.
- **Coordinates are in image space** — screenshots are downscaled to 1280px long edge before being sent to Claude (token cost & latency). `AgentService.performTap` scales them back up to the actual device resolution.
- **History without re-sending images** — every loop step replays prior turns as text only. Re-sending screenshots each turn would burn tokens for marginal benefit.
- **Two models** — Haiku 4.5 for routing (fast), Sonnet 4.6 for the vision loop (capable). Edit `ClaudeApiClient.MODEL_VISION` / `MODEL_TEXT` to swap.
- **One action per turn** — the prompts teach Claude to emit a single atomic action and re-observe. Borrowed directly from mobile-use's Cortex (their `unpredictable actions = isolate them` rule).
- **Min SDK 30** — older devices would need a MediaProjection fallback in `ScreenCaptureManager`.

## Security

- Your API key sits in `BuildConfig` (compiled into the APK). Fine for personal builds and TestFlight-style closed beta. **Do not ship to Play Store this way** — proxy the API through your own backend in production so the key never leaves your servers.
- Accessibility Service is a powerful permission. The app only runs the agent loop while a user-initiated voice command is being processed (and shows a foreground notification while it's working).
- The agent never auto-responds to messages or calls without an explicit voice request from the user.

## Roadmap

- Multi-step planner agent (mobile-use's Planner) for tasks beyond 12 steps
- Structured "element targeting" — feed Claude the accessibility tree alongside the screenshot so it can pick by `resource-id` instead of pixels (more reliable)
- Local Whisper for fully-offline voice in
- Urdu / multilingual prompts
