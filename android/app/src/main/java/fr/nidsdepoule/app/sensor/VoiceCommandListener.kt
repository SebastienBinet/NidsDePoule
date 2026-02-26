package fr.nidsdepoule.app.sensor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Continuous voice command listener for hands-free pothole reporting.
 *
 * Listens for spoken keywords and fires callbacks:
 *   - "Almost" sounds → onAlmost  (e.g. "iiiii", "attention", "il y en a")
 *   - "Hit" sounds    → onHit     (e.g. "ayoye", "ouch", "aille", "aïe")
 *
 * Uses Android's built-in SpeechRecognizer. Requires RECORD_AUDIO permission.
 * Automatically restarts listening after each recognition result or error.
 */
class VoiceCommandListener(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandListener"

        // Keywords that trigger an "Almost" report (seeing a pothole ahead).
        private val ALMOST_KEYWORDS = listOf(
            "attention", "il y en a", "regarde", "trou",
            "ouf", "iiiii", "pothole", "watch", "look",
        )

        // Keywords that trigger a "Hit" report (just drove over one).
        private val HIT_KEYWORDS = listOf(
            "ayoye", "ouch", "aille", "aie", "aïe",
            "merde", "tabarnak", "shit", "ow", "bang",
        )
    }

    /** Callback when an "Almost" voice command is detected. */
    var onAlmost: (() -> Unit)? = null

    /** Callback when a "Hit" voice command is detected. */
    var onHit: (() -> Unit)? = null

    /** Observable state for the UI — true when microphone is actively listening. */
    var isListening by mutableStateOf(false)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var running = false

    /**
     * Start continuous listening. Requires RECORD_AUDIO permission to already be granted.
     */
    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }
        running = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
        startListening()
    }

    fun stop() {
        running = false
        isListening = false
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
    }

    private fun startListening() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CANADA_FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Short silence = restart quickly for continuous listening
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            // Suppress the system "beep" sound on each listen cycle
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            recognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start listening", e)
            isListening = false
            // Retry after a short delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startListening()
            }, 2000)
        }
    }

    private fun processResults(texts: List<String>) {
        for (text in texts) {
            val lower = text.lowercase()
            Log.d(TAG, "Heard: '$lower'")

            // Check Hit keywords first (more urgent — just drove over one)
            if (HIT_KEYWORDS.any { lower.contains(it) }) {
                Log.i(TAG, "Hit command detected: '$lower'")
                onHit?.invoke()
                return
            }

            // Check Almost keywords (seeing one ahead)
            if (ALMOST_KEYWORDS.any { lower.contains(it) }) {
                Log.i(TAG, "Almost command detected: '$lower'")
                onAlmost?.invoke()
                return
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Keep isListening = true while running — we'll restart immediately
            // in onResults/onError. Only drop it when truly stopped.
            if (!running) isListening = false
        }

        override fun onError(error: Int) {
            // Common errors: ERROR_NO_MATCH (7), ERROR_SPEECH_TIMEOUT (6)
            // Restart listening unless we've been stopped
            if (running) {
                val delay = if (error == SpeechRecognizer.ERROR_NO_MATCH) 200L else 1000L
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startListening()
                }, delay)
            } else {
                isListening = false
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            processResults(matches)
            // Restart listening for continuous mode
            if (running) {
                startListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            processResults(matches)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
