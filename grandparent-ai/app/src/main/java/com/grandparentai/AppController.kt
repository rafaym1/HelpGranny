package com.grandparentai

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point that takes a user request (typed, voice-recognised, broadcast-triggered)
 * and runs it through Orchestrator → AgentService.
 *
 * Centralised here so the wake-word service, the incoming-call receiver, and the SMS receiver
 * can all kick off tasks without needing the activity to be in the foreground.
 */
object AppController {

    private const val TAG = "AppController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Only one task at a time — concurrent agent loops would fight over the screen. */
    private val taskMutex = Mutex()

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    /**
     * Submit a free-form user request. Routes through [Orchestrator]. If the route is a
     * specialist agent, runs the agent loop and speaks the final result. Returns immediately —
     * work happens on a coroutine.
     */
    fun submit(ctx: Context, userText: String) {
        if (userText.isBlank()) return

        scope.launch {
            taskMutex.withLock {
                running = true
                AgentForegroundService.start(ctx)
                try {
                    runOnce(userText)
                } catch (t: Throwable) {
                    Log.e(TAG, "task failed", t)
                    AgentBridge.log("❌ ${t.message}")
                    Speech.speak("Sorry, something went wrong.", interrupt = true)
                } finally {
                    running = false
                    AgentForegroundService.stop(ctx)
                    AgentBridge.setStatus("")
                }
            }
        }
    }

    /**
     * Submit a request that should bypass routing and go straight to a specific specialist
     * agent. Used by the incoming-call/SMS receivers (they always go to ScamShield).
     */
    fun submitToAgent(ctx: Context, route: Orchestrator.Route, taskText: String) {
        if (route == Orchestrator.Route.DIRECT) {
            submit(ctx, taskText); return
        }
        scope.launch {
            taskMutex.withLock {
                running = true
                AgentForegroundService.start(ctx)
                try {
                    runForcedRoute(route, taskText)
                } catch (t: Throwable) {
                    Log.e(TAG, "forced-route task failed", t)
                    AgentBridge.log("❌ ${t.message}")
                    Speech.speak("Sorry, something went wrong.", interrupt = true)
                } finally {
                    running = false
                    AgentForegroundService.stop(ctx)
                    AgentBridge.setStatus("")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun runOnce(userText: String) {
        val service = AgentBridge.service ?: run {
            Speech.speak(
                "I need accessibility permission to do that. Please open Grandparent AI and turn it on.",
                interrupt = true,
            )
            return
        }

        AgentBridge.log("🎤 \"$userText\"")
        AgentBridge.setStatus("Thinking…")

        val routed = Orchestrator.route(userText)
        AgentBridge.log("→ route=${routed.route} intent=\"${routed.intent}\"")
        if (routed.route == Orchestrator.Route.SCAM_SHIELD) AgentBridge.verdict.value = Verdict.NONE

        val finalMessage = when (routed.route) {
            Orchestrator.Route.DIRECT -> routed.spokenResponse.ifBlank {
                "I'm not sure how to help with that yet."
            }
            else -> service.runAgentLoop(
                task = routed.intent,
                systemPrompt = Orchestrator.promptFor(routed.route),
            )
        }

        AgentBridge.log("✅ $finalMessage")
        Speech.speak(finalMessage, interrupt = false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun runForcedRoute(route: Orchestrator.Route, taskText: String) {
        val service = AgentBridge.service ?: return
        AgentBridge.log("⚡ auto-route=$route — \"$taskText\"")
        if (route == Orchestrator.Route.SCAM_SHIELD) AgentBridge.verdict.value = Verdict.NONE
        val finalMessage = service.runAgentLoop(
            task = taskText,
            systemPrompt = Orchestrator.promptFor(route),
        )
        AgentBridge.log("✅ $finalMessage")
        Speech.speak(finalMessage, interrupt = false)
    }
}
