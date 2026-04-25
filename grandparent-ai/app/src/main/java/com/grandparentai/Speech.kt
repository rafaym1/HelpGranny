package com.grandparentai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide TTS. Used by the activity, the agent service, and the wake-word service so that
 * voice output works whether the user has the app open or not.
 *
 * We initialise lazily on first use and keep the engine alive for the process lifetime — TTS
 * init is slow (300ms+ on cold start), and we don't want to pay that cost mid-task.
 */
object Speech {
    private const val TAG = "Speech"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private val nextId = AtomicLong(1L)

    fun init(ctx: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    tts?.setSpeechRate(0.95f) // a touch slower for elderly listeners
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = Unit
                        @Deprecated("Deprecated in API 21")
                        override fun onError(utteranceId: String?) {
                            Log.w(TAG, "TTS utterance error: $utteranceId")
                        }
                    })
                } else Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    /** Speak [text]. If [interrupt] is true, flushes any in-progress speech. */
    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        val engine = tts ?: return
        if (!ready) return
        val id = "ga-${nextId.getAndIncrement()}"
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, id)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
