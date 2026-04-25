package com.grandparentai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Foreground service that listens continuously for the wake word **"helper"**. When detected,
 * the rest of the spoken phrase is treated as the command and submitted to [AppController].
 *
 * This is deliberately a "soft" hot-word implementation built on top of [SpeechRecognizer]:
 *   - It works on stock Android with no extra SDKs/models.
 *   - It is meaningfully battery-heavier than a real on-device hot-word engine (Porcupine,
 *     Snowboy). For a production app we'd bundle Porcupine; that's noted in the README.
 *
 * The listening loop self-restarts on every result/error so we don't go silent after a single
 * timeout. While the agent is mid-task we pause listening (the agent's TTS output would
 * otherwise feed back into the recogniser).
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ID = "grandparent_ai_wake"
        private const val NOTIF_ID = 0xA902

        const val WAKE_WORD = "helper"
        /** Brief delay between recogniser sessions — too short and some OEMs throw "busy". */
        private const val RESTART_DELAY_MS = 350L

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        Speech.init(this)
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------- listening loop

    private fun startListening() {
        if (stopping) return
        // Don't listen while the agent is already running — its TTS would echo into the mic.
        if (AppController.isRunning()) {
            handler.postDelayed({ startListening() }, 1500L)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "speech recognition unavailable")
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(Listener())
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        handler.postDelayed({ startListening() }, RESTART_DELAY_MS)
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onError(error: Int) {
            // Almost every error here means "no speech" or "recognizer ready for new session".
            // We just restart. Specifically logging only the surprising ones.
            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                error != SpeechRecognizer.ERROR_CLIENT
            ) {
                Log.w(TAG, "recognizer error: $error")
            }
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: arrayListOf()
            handleMatches(matches)
            scheduleRestart()
        }
    }

    private fun handleMatches(matches: List<String>) {
        if (matches.isEmpty()) return
        for (m in matches) {
            val command = extractCommandAfterWake(m) ?: continue
            AgentBridge.log("🔔 wake word heard: \"$m\"")
            // Pause listening; AppController will re-enable us by virtue of the running-flag check.
            try { recognizer?.cancel() } catch (_: Throwable) {}
            if (command.isBlank()) {
                Speech.speak("Yes? I'm listening. Tap the button or say what you need.", interrupt = true)
            } else {
                Speech.speak("On it.", interrupt = true)
                AppController.submit(this, command)
            }
            return
        }
    }

    /**
     * Returns the user's command (what came AFTER "helper"), or null if the wake word wasn't
     * present. Returns "" if "helper" appeared by itself.
     */
    private fun extractCommandAfterWake(utterance: String): String? {
        val lower = utterance.lowercase(Locale.ROOT).trim()
        val idx = lower.indexOf(WAKE_WORD)
        if (idx < 0) return null
        // Walk past the wake word + any punctuation/whitespace.
        var afterIdx = idx + WAKE_WORD.length
        while (afterIdx < lower.length && !lower[afterIdx].isLetterOrDigit()) afterIdx++
        return if (afterIdx >= lower.length) "" else utterance.substring(afterIdx).trim()
    }

    // -------------------------------------------------------------------- notification

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.wake_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.wake_notif_title))
            .setContentText(getString(R.string.wake_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .build()
    }
}
