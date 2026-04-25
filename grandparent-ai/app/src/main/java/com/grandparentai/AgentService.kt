package com.grandparentai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The brain + hands. Reads the screen via [AccessibilityService.takeScreenshot] and the
 * accessibility tree, and performs taps / scrolls / typing through Accessibility APIs.
 *
 * The orchestration logic (which agent prompt to load) lives in [Orchestrator]. This class is
 * the loop body: capture → ask Claude → speak narration → execute → wait → repeat.
 */
class AgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentService"
        private const val DEFAULT_MAX_STEPS = 12
        /** Pause after each action so the screen can settle before the next screenshot. */
        private const val POST_ACTION_DELAY_MS = 500L
        /** Pause after an unpredictable navigation (HOME, BACK, app launch). */
        private const val POST_NAV_DELAY_MS = 1100L
        /** Cap history (text-only replays) to keep prompt small. */
        private const val MAX_HISTORY_TURNS = 8 // = 4 user/assistant pairs
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")
        AgentBridge.attach(this)
        AgentBridge.log("Accessibility service connected.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AgentBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: we drive the loop on demand from MainActivity rather than reacting to events.
    }

    override fun onInterrupt() = Unit

    // -------------------------------------------------------------------- agent loop

    /**
     * Run the per-task agent loop until Claude says DONE, we hit [maxSteps], or [stopFlag] flips.
     * Speaks a friendly status line before each step and plays a soft repeating tone throughout
     * so the user knows the app is alive.
     *
     * @return user-facing message to speak back via TTS at the end.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun runAgentLoop(
        task: String,
        systemPrompt: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        stopFlag: () -> Boolean = { false },
    ): String {
        AgentBridge.log("▶ Task: $task")
        Speech.speak("On it.", interrupt = true)
        Tone.start()

        try {
            val history = mutableListOf<ClaudeApiClient.Turn>()

            for (step in 1..maxSteps) {
                if (stopFlag()) {
                    AgentBridge.log("Stopped by user.")
                    return "Cancelled."
                }

                AgentBridge.setStatus("Looking at the screen…")
                val capture = ScreenCaptureManager.capture(this) ?: run {
                    AgentBridge.log("Could not capture screen.")
                    return "I couldn't see the screen. Please try again."
                }

                AgentBridge.setStatus("Thinking…")
                val userText = if (step == 1) {
                    "TASK: $task\n\nThis is the current screen. What is the next single action?"
                } else {
                    "Current screen after the previous action. What is the next single action? " +
                        "If the task is complete, respond with DONE: <message>."
                }

                val reply = try {
                    ClaudeApiClient.sendWithImage(
                        systemPrompt = systemPrompt,
                        userMessage = userText,
                        imageBase64 = capture.base64,
                        history = history.takeLast(MAX_HISTORY_TURNS),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Claude call failed", t)
                    AgentBridge.log("Claude error: ${t.message}")
                    return "I'm having trouble reaching the assistant. Please try again."
                }

                history.add(ClaudeApiClient.Turn(role = "user", text = userText, hasImage = true))
                history.add(ClaudeApiClient.Turn(role = "assistant", text = reply, hasImage = false))

                // Surface scam-shield verdict (no-op outside scam-shield runs).
                Verdict.parse(reply).takeIf { it != Verdict.NONE }?.let { v ->
                    AgentBridge.verdict.value = v
                    AgentBridge.log("⚖ verdict=$v")
                }

                val turn = ActionParser.parse(reply)
                AgentBridge.log("Step $step → ${turn.action.summary()}")

                // Speak the narration for non-DONE steps; DONE is announced separately as the
                // final result so it's clearly the wrap-up message.
                if (turn.action !is Action.Done && turn.narration.isNotBlank()) {
                    Speech.speak(turn.narration, interrupt = false)
                    AgentBridge.setStatus(turn.narration)
                }

                var lastActionWasNavigation = false
                when (val a = turn.action) {
                    is Action.Done -> return a.message.ifBlank { "All done." }
                    is Action.Tap -> performTap(a.x, a.y, capture)
                    is Action.Type -> performType(a.text)
                    is Action.ScrollDown -> performScroll(down = true)
                    is Action.ScrollUp -> performScroll(down = false)
                    is Action.Back -> {
                        performGlobalAction(GLOBAL_ACTION_BACK); lastActionWasNavigation = true
                    }
                    is Action.Home -> {
                        performGlobalAction(GLOBAL_ACTION_HOME); lastActionWasNavigation = true
                    }
                    is Action.Recents -> {
                        performGlobalAction(GLOBAL_ACTION_RECENTS); lastActionWasNavigation = true
                    }
                    is Action.Wait -> Unit
                    is Action.Unknown -> {
                        AgentBridge.log("Unknown action — model said: ${reply.take(120)}")
                    }
                }

                delay(if (lastActionWasNavigation) POST_NAV_DELAY_MS else POST_ACTION_DELAY_MS)
            }

            AgentBridge.log("Hit max steps.")
            return "I tried but couldn't finish that one. Want me to try again?"
        } finally {
            Tone.stop()
        }
    }

    // -------------------------------------------------------------------- gestures

    /**
     * The model returns coordinates in the *image* space (the screenshot we sent it). The
     * device display can be larger if we downscaled before encoding — scale back up here.
     */
    private suspend fun performTap(imgX: Float, imgY: Float, capture: ScreenCaptureManager.Capture) {
        val deviceX = (imgX * capture.xScale).coerceIn(0f, (capture.deviceWidth - 1).toFloat())
        val deviceY = (imgY * capture.yScale).coerceIn(0f, (capture.deviceHeight - 1).toFloat())
        AgentBridge.log("  tap @ ($deviceX, $deviceY)")

        val path = Path().apply { moveTo(deviceX, deviceY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGestureAndAwait(gesture)
    }

    private suspend fun performScroll(down: Boolean) {
        val display = resources.displayMetrics
        val midX = display.widthPixels / 2f
        val startY: Float
        val endY: Float
        if (down) {
            startY = display.heightPixels * 0.75f
            endY = display.heightPixels * 0.25f
        } else {
            startY = display.heightPixels * 0.25f
            endY = display.heightPixels * 0.75f
        }
        AgentBridge.log("  scroll ${if (down) "down" else "up"}")
        val path = Path().apply {
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
            .build()
        dispatchGestureAndAwait(gesture)
    }

    private fun performType(text: String) {
        val focused = findFocusedEditable()
        if (focused == null) {
            AgentBridge.log("  type: no focused text field; trying first editable")
            findFirstEditable(rootInActiveWindow)?.let { node ->
                fillText(node, text); return
            }
            return
        }
        fillText(focused, text)
    }

    private fun fillText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        AgentBridge.log("  type: \"${text.take(60)}\" → ${if (ok) "ok" else "failed"}")
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        return findFirstEditable(root)
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            val hit = findFirstEditable(c)
            if (hit != null) return hit
        }
        return null
    }

    private suspend fun dispatchGestureAndAwait(gesture: GestureDescription) {
        withContext(Dispatchers.Main) {
            dispatchGesture(gesture, null, null)
        }
    }
}
