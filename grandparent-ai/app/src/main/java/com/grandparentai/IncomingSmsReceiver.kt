package com.grandparentai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Auto-runs Scam Shield against the body of a freshly-arrived SMS.
 *
 * Note: we do NOT auto-act on the message (no auto-deleting, no auto-replying). The agent
 * reads the message text, looks at the screen, and either reassures the user it's safe or
 * warns them that it looks like a scam — never destructive.
 */
class IncomingSmsReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "IncomingSms" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        Log.i(TAG, "incoming SMS from \"$sender\" (${body.length} chars)")

        val task = buildString {
            append("A new SMS just arrived")
            if (sender.isNotBlank()) append(" from \"$sender\"")
            append(". The message text is:\n\n")
            append(body.take(800))
            append("\n\nDecide whether this looks like a scam. If it does, warn the user clearly. ")
            append("If it looks normal, reassure them. Do NOT delete or reply.")
        }

        AppController.submitToAgent(context.applicationContext, Orchestrator.Route.SCAM_SHIELD, task)
    }
}
