package com.grandparentai

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.grandparentai.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The user-facing surface. Two ways to drive a task:
 *   1. Tap the big blue button (one-shot recognition).
 *   2. Toggle "Always listen for Helper" (continuous wake-word service).
 *
 * Background-triggered tasks (incoming call/SMS) don't need the activity to be open at all.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_NAME = "grandparent_prefs"
        private const val PREF_WAKE_ENABLED = "wake_enabled"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var speechRecognizer: SpeechRecognizer? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else toast("Microphone permission is needed for voice input.")
    }

    private val wakePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            WakeWordService.start(this)
            prefs.edit().putBoolean(PREF_WAKE_ENABLED, true).apply()
            binding.switchWake.isChecked = true
            toast("Wake word on. Say \"Helper\" anytime.")
        } else {
            binding.switchWake.isChecked = false
            toast("Microphone permission is needed for the wake word.")
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* whatever — only affects the foreground notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        Speech.init(this)

        if (BuildConfig.ANTHROPIC_API_KEY.isBlank()) {
            binding.tvStatus.text = getString(R.string.missing_api_key)
        } else {
            binding.tvStatus.text = getString(R.string.status_idle)
        }

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        binding.btnTalk.setOnClickListener { onTalkPressed() }

        binding.switchWake.isChecked = prefs.getBoolean(PREF_WAKE_ENABLED, false)
        binding.switchWake.setOnCheckedChangeListener { _, checked ->
            if (checked) enableWakeWord() else disableWakeWord()
        }

        observeAgentBridge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) {
            ensureAccessibilityEnabled()
        }
        // If the user enabled wake word in a prior session, make sure the service is up.
        if (binding.switchWake.isChecked && hasMic()) WakeWordService.start(this)
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // -------------------------------------------------------------------- talk button

    private fun onTalkPressed() {
        if (AppController.isRunning()) {
            toast("Already working on something. One moment…")
            return
        }
        if (BuildConfig.ANTHROPIC_API_KEY.isBlank()) {
            toast(getString(R.string.missing_api_key)); return
        }
        if (AgentBridge.service == null) {
            ensureAccessibilityEnabled(); return
        }
        if (!hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition isn't available on this device."); return
        }
        // Pause the wake-word service while the activity-level recognizer runs — they fight
        // over the microphone otherwise.
        WakeWordService.stop(this)

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(VoiceListener())
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        AgentBridge.setStatus(getString(R.string.status_listening))
        binding.btnTalk.isEnabled = false
        speechRecognizer?.startListening(intent)
    }

    private inner class VoiceListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onError(error: Int) {
            binding.btnTalk.isEnabled = true
            AgentBridge.setStatus(getString(R.string.status_idle))
            // Restart wake word if the user had it on.
            if (binding.switchWake.isChecked) WakeWordService.start(this@MainActivity)
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "I didn't hear anything. Try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone permission is missing."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Network problem during voice recognition."
                else -> "Couldn't recognise that ($error). Try again."
            }
            toast(msg)
        }

        override fun onResults(results: Bundle?) {
            binding.btnTalk.isEnabled = true
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                AgentBridge.setStatus(getString(R.string.status_idle))
                if (binding.switchWake.isChecked) WakeWordService.start(this@MainActivity)
                toast("I didn't hear anything. Try again.")
                return
            }
            AppController.submit(this@MainActivity, text)
            // Restart wake word once the agent finishes (the service self-pauses while
            // AppController.isRunning() is true).
            if (binding.switchWake.isChecked) WakeWordService.start(this@MainActivity)
        }
    }

    // -------------------------------------------------------------------- wake word

    private fun enableWakeWord() {
        val needed = mutableListOf<String>()
        if (!hasMic()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) {
            WakeWordService.start(this)
            prefs.edit().putBoolean(PREF_WAKE_ENABLED, true).apply()
            toast("Wake word on. Say \"Helper\" anytime.")
        } else {
            wakePermissions.launch(needed.toTypedArray())
        }
    }

    private fun disableWakeWord() {
        WakeWordService.stop(this)
        prefs.edit().putBoolean(PREF_WAKE_ENABLED, false).apply()
    }

    // -------------------------------------------------------------------- bridge wiring

    private fun observeAgentBridge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AgentBridge.status.collectLatest { s ->
                        if (s.isNotBlank()) binding.tvStatus.text = s
                    }
                }
                launch {
                    AgentBridge.logLines.collectLatest { line ->
                        val current = binding.tvLog.text?.toString().orEmpty()
                        val next = if (current.isBlank()) line else "$current\n$line"
                        binding.tvLog.text = next.takeLast(4000)
                    }
                }
                launch {
                    AgentBridge.verdict.collectLatest { v -> renderVerdict(v) }
                }
            }
        }
    }

    private fun renderVerdict(v: Verdict) {
        when (v) {
            Verdict.NONE -> binding.tvVerdict.visibility = android.view.View.GONE
            Verdict.SCAM -> showVerdictBanner(R.string.verdict_scam, R.color.verdict_scam_bg)
            Verdict.SAFE -> showVerdictBanner(R.string.verdict_safe, R.color.verdict_safe_bg)
            Verdict.UNSURE -> showVerdictBanner(R.string.verdict_unsure, R.color.verdict_unsure_bg)
        }
    }

    private fun showVerdictBanner(textRes: Int, bgColorRes: Int) {
        binding.tvVerdict.setText(textRes)
        binding.tvVerdict.setBackgroundResource(bgColorRes)
        binding.tvVerdict.visibility = android.view.View.VISIBLE
    }

    // -------------------------------------------------------------------- helpers

    private fun ensureAccessibilityEnabled() {
        if (AgentBridge.service != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_accessibility_title)
            .setMessage(R.string.enable_accessibility_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
