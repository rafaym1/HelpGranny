package com.grandparentai

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Soft repeating "I'm working" cue. Plays a low-volume short beep every ~3 seconds while a task
 * is running — quiet enough not to be annoying, loud enough that the user knows the app is alive.
 *
 * Uses [ToneGenerator] so we don't need to ship audio assets.
 */
object Tone {

    /** 0..100. ToneGenerator volume. */
    private const val VOLUME = 22

    /** Tone duration in ms. */
    private const val BEEP_MS = 110

    /** Interval between beeps in ms. */
    private const val INTERVAL_MS = 2800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var loopJob: Job? = null
    @Volatile private var generator: ToneGenerator? = null

    fun start() {
        if (loopJob?.isActive == true) return
        try {
            generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
        } catch (_: Throwable) {
            // Some OEMs throw when the audio focus is held by another app — skip the tone in that
            // case rather than crashing.
            return
        }
        loopJob = scope.launch {
            while (isActive) {
                generator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        generator?.release()
        generator = null
    }
}
