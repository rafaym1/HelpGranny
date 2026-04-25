package com.grandparentai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Auto-launches the Scam Shield agent when an incoming call rings. The agent reads the call
 * screen — caller ID, number, "Spam" badge if the dialer flagged it — and decides whether to
 * decline + warn, or stand down.
 *
 * Why broadcast vs. accessibility window-state events: a broadcast fires before the user picks
 * up, even if our accessibility loop is busy. Window-state would also work but is racier.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCall"
        @Volatile private var lastHandledNumber: String? = null
        @Volatile private var lastHandledAt: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()

        // Phone state can fire RINGING twice for the same call on some OEMs. Debounce.
        val now = System.currentTimeMillis()
        if (number == lastHandledNumber && now - lastHandledAt < 8_000L) return
        lastHandledNumber = number; lastHandledAt = now

        Log.i(TAG, "incoming call ringing: \"$number\"")

        val task = if (number.isBlank()) {
            "An incoming call is ringing on the screen with no caller ID. Look at the screen and " +
                "decide if it's likely a scam. If it is, decline it and warn the user."
        } else {
            "An incoming call is ringing from \"$number\". Look at the screen, decide if it is " +
                "likely a scam, and act accordingly."
        }

        AppController.submitToAgent(context.applicationContext, Orchestrator.Route.SCAM_SHIELD, task)
    }
}
