package com.grandparentai

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton hand-off between MainActivity and AgentService.
 * AccessibilityService is owned by the system — we can't hold a direct reference to it from
 * the activity, so we expose it through this bridge once onServiceConnected fires.
 */
object AgentBridge {

    private const val TAG = "AgentBridge"

    @Volatile
    var service: AgentService? = null
        private set

    /** Activity-visible status text ("Listening…", "Thinking…", "Tapping send button"…). */
    val status: MutableStateFlow<String> = MutableStateFlow("")

    /** Latest Scam Shield verdict (NONE outside of scam-shield runs). */
    val verdict: MutableStateFlow<Verdict> = MutableStateFlow(Verdict.NONE)

    /** Append-only log of agent steps; UI tails the latest entries. */
    val logLines: MutableSharedFlow<String> = MutableSharedFlow(replay = 50, extraBufferCapacity = 64)

    fun attach(svc: AgentService) {
        service = svc
        Log.i(TAG, "AgentService attached")
    }

    fun detach(svc: AgentService) {
        if (service === svc) service = null
        Log.i(TAG, "AgentService detached")
    }

    fun setStatus(s: String) {
        status.value = s
    }

    fun log(line: String) {
        Log.i("AgentLog", line)
        logLines.tryEmit(line)
    }
}
