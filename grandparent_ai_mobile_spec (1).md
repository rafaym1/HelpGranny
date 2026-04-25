# Grandparent AI — Mobile App Spec
**Claude-powered Mobile Use Agent for Android**

---

## Core Concept
This is computer use — but for mobile.

An Android app that uses the **Accessibility Service API** to:
- See every screen on the phone (like Claude computer use sees a desktop)
- Tap, type, scroll, navigate — across any app
- Powered by Claude API as the brain

No laptop. No ADB. The phone controls itself.

---

## Why Accessibility Service (Not ADB)

| ADB approach | Accessibility Service |
|---|---|
| Requires laptop connected | Runs on the phone itself |
| Not a real product | This IS a real app |
| Needs USB debugging | Standard Android API |
| Can't ship to users | Can publish to Play Store |
| Judges see a laptop hack | Judges see a mobile product |

---

## Tech Stack

| Layer | Tool |
|---|---|
| Platform | Android (Kotlin or React Native) |
| Screen Reading | Android Accessibility Service |
| Screen Capture | MediaProjection API |
| Agent Brain | Claude API (`claude-sonnet-4-20250514`) with vision |
| Action Execution | AccessibilityService.performAction() |
| Voice Input | Android SpeechRecognizer (built-in, free) |
| Voice Output | Android TextToSpeech (built-in, free) |
| Backend | None needed — Claude API called directly from app |

---

## Architecture

```
User speaks into phone
        ↓
Android SpeechRecognizer → text
        ↓
Orchestrator Agent (Claude API)
  - classifies intent
  - routes to correct agent
        ↓
Agent takes screenshot (MediaProjection API)
        ↓
Screenshot + task → Claude API (vision)
        ↓
Claude responds: "Tap at x:540 y:1200"
        ↓
AccessibilityService.performAction(tap, x, y)
        ↓
New screenshot taken
        ↓
Loop until task complete
        ↓
TextToSpeech speaks result to user
```

---

## Android Project Structure

```
grandparent-ai/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/grandparentai/
│   │   ├── MainActivity.kt           # Main UI, voice input, TTS
│   │   ├── AgentService.kt           # Accessibility Service (the core)
│   │   ├── ScreenCaptureManager.kt   # MediaProjection screenshots
│   │   ├── ClaudeApiClient.kt        # Claude API calls
│   │   ├── Orchestrator.kt           # Routes to correct agent
│   │   └── agents/
│   │       ├── ScamShieldAgent.kt
│   │       ├── EmergencyAgent.kt
│   │       └── WhatsAppAgent.kt
│   └── res/
│       └── layout/activity_main.xml  # Simple UI
```

---

## AndroidManifest.xml (Critical Permissions)

```xml
<manifest>
  <!-- Accessibility Service -->
  <service
    android:name=".AgentService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
      <action android:name="android.accessibilityservice.AccessibilityService"/>
    </intent-filter>
    <meta-data
      android:name="android.accessibilityservice"
      android:resource="@xml/accessibility_service_config"/>
  </service>

  <!-- Permissions -->
  <uses-permission android:name="android.permission.INTERNET"/>
  <uses-permission android:name="android.permission.RECORD_AUDIO"/>
  <uses-permission android:name="android.permission.CALL_PHONE"/>
  <uses-permission android:name="android.permission.READ_CONTACTS"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
</manifest>
```

---

## AgentService.kt — The Core Engine

```kotlin
class AgentService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Called whenever screen changes — not needed for our use case
    }

    // MAIN AGENT LOOP
    suspend fun runAgentLoop(task: String, systemPrompt: String): String {
        val maxSteps = 10
        
        for (step in 0 until maxSteps) {
            // 1. Take screenshot
            val screenshot = ScreenCaptureManager.capture() // returns Base64 string
            
            // 2. Send to Claude
            val response = ClaudeApiClient.sendWithImage(
                systemPrompt = systemPrompt,
                userMessage = if (step == 0) task else "Current screen. What is your next action?",
                imageBase64 = screenshot
            )
            
            // 3. Parse and execute action
            when {
                response.contains("TAP:") -> {
                    val (x, y) = parseCoords(response)
                    performTap(x, y)
                }
                response.contains("TYPE:") -> {
                    val text = parseText(response)
                    performType(text)
                }
                response.contains("SCROLL_DOWN") -> performScroll(down = true)
                response.contains("SCROLL_UP") -> performScroll(down = false)
                response.contains("BACK") -> performGlobalAction(GLOBAL_ACTION_BACK)
                response.contains("HOME") -> performGlobalAction(GLOBAL_ACTION_HOME)
                response.contains("DONE:") -> return parseDoneMessage(response)
            }
            
            delay(800) // wait for screen to update
        }
        return "Task completed"
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performType(text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        findFocusedTextField()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
```

---

## ClaudeApiClient.kt

```kotlin
object ClaudeApiClient {
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-4-20250514"
    // API key — store in BuildConfig or encrypted prefs, never hardcode in production
    private const val API_KEY = BuildConfig.ANTHROPIC_API_KEY

    suspend fun sendWithImage(
        systemPrompt: String,
        userMessage: String,
        imageBase64: String
    ): String {
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1000)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        // Image
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", imageBase64)
                            })
                        })
                        // Text
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", userMessage)
                        })
                    })
                })
            })
        }

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", API_KEY)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val json = JSONObject(response.body!!.string())
        return json.getJSONArray("content").getJSONObject(0).getString("text")
    }
}
```

---

## Orchestrator.kt

```kotlin
object Orchestrator {

    private val SYSTEM_PROMPT = """
        You are helping elderly people use their smartphone via voice.
        
        Classify the user's intent and route to the right agent.
        
        ROUTING (respond with JSON only):
        {
          "route": "SCAM_SHIELD" | "EMERGENCY" | "WHATSAPP" | "DIRECT",
          "intent": "what the user wants",
          "spoken_response": "if DIRECT, what to say back"
        }
        
        Rules:
        - Scam/suspicious call/prize/bank/urgent money → SCAM_SHIELD
        - Fell/pain/emergency/help → EMERGENCY  
        - Message/WhatsApp/call/contact → WHATSAPP
        - Everything else → DIRECT
        
        Understand elderly speech: vague references ("him", "my son", "the blue one"),
        repetition, slow phrasing. Always be warm and patient.
    """.trimIndent()

    suspend fun route(userText: String): RouteResult {
        val response = ClaudeApiClient.sendTextOnly(SYSTEM_PROMPT, userText)
        return parseRouteResult(response)
    }
}
```

---

## Agent Prompts

### Scam Shield
```kotlin
val SCAM_SHIELD_PROMPT = """
You are protecting an elderly person from phone scams.

Look at the screen. Detect:
- Unknown caller claiming to be bank/government/telecom
- Messages with urgent language, prize claims, OTP requests
- Requests for money, account details, PIN

When you detect a threat:
1. TAP: the decline/block button if visible
2. DONE: Explain it's a scam in simple language. Say WHY. Offer to block + alert family.

Example response: "DONE: This is a scam. Real banks never call asking for your PIN. 
I blocked this number. Should I let Ahmed know about this call?"

ACTIONS: TAP: x y | TYPE: text | BACK | HOME | DONE: message
""".trimIndent()
```

### Emergency Agent
```kotlin
val EMERGENCY_PROMPT = """
EMERGENCY MODE. Someone needs help RIGHT NOW.

Steps (do them fast, in order):
1. HOME - go to home screen
2. Open Phone app - TAP on it
3. Find "Emergency Contact" or first favorite contact - TAP
4. Confirm call - TAP dial button
5. DONE: Tell user you called them and help is coming

Do not hesitate. Do not ask for confirmation. Act immediately.

ACTIONS: TAP: x y | HOME | BACK | DONE: message
""".trimIndent()
```

### WhatsApp Agent
```kotlin
val WHATSAPP_PROMPT = """
You are helping an elderly person send a WhatsApp message.

Steps:
1. HOME - go to home screen
2. Find and tap WhatsApp
3. Tap search icon, type contact name
4. Tap the contact
5. Tap message field
6. TYPE: the message (write it naturally, in their voice, not formal)
7. Tap send button
8. DONE: confirm what you sent and to whom

Always write messages warmly. "Had my lunch, feeling better!" not "I have consumed my meal."

ACTIONS: TAP: x y | TYPE: text | BACK | HOME | SCROLL_DOWN | DONE: message
""".trimIndent()
```

---

## MainActivity.kt — UI

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var agentService: AgentService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupVoiceInput()
        setupTTS()
        checkAccessibilityPermission()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") // or ur-PK for Urdu
        }
        speechRecognizer.startListening(intent)
    }

    private fun onVoiceInput(text: String) {
        updateStatus("Thinking...") // show in UI
        
        lifecycleScope.launch {
            val route = Orchestrator.route(text)
            
            val result = when (route.type) {
                "SCAM_SHIELD" -> agentService.runAgentLoop(route.intent, SCAM_SHIELD_PROMPT)
                "EMERGENCY" -> agentService.runAgentLoop(route.intent, EMERGENCY_PROMPT)
                "WHATSAPP" -> agentService.runAgentLoop(route.intent, WHATSAPP_PROMPT)
                else -> route.spokenResponse
            }
            
            speak(result) // TTS speaks back to user
            updateStatus("Listening...") // ready for next command
        }
    }
    
    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
}
```

---

## Simple UI Layout (activity_main.xml)

Keep it minimal — one big button, status text, agent activity log.

```xml
<LinearLayout>
    <!-- Big tap-to-talk button -->
    <Button
        android:id="@+id/btnTalk"
        android:layout_width="200dp"
        android:layout_height="200dp"
        android:text="TAP TO TALK"
        android:textSize="24sp"
        android:backgroundTint="#2196F3"/>

    <!-- Status text -->
    <TextView
        android:id="@+id/tvStatus"
        android:text="Listening..."
        android:textSize="20sp"/>

    <!-- Agent activity log -->
    <TextView
        android:id="@+id/tvLog"
        android:text=""/>
</LinearLayout>
```