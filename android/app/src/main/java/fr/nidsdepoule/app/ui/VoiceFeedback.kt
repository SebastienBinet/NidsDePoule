package fr.nidsdepoule.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * French text-to-speech voice feedback for pothole reports.
 *
 * Almost (severity < 3) → "Attention !"
 * Hit (severity >= 3)   → "AYOYE !"
 */
class VoiceFeedback(private val context: Context) {

    companion object {
        private const val TAG = "VoiceFeedback"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var initStarted = false

    /**
     * Start TTS initialization on a background thread.
     * Call early so TTS is ready by the time the user triggers a report.
     */
    fun ensureInitialized() {
        if (initStarted) return
        initStarted = true
        Thread({
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.CANADA_FRENCH)
                        ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.FRENCH)
                    }
                    tts?.setSpeechRate(1.3f)
                    ready = true
                    Log.i(TAG, "TTS initialized in French")
                } else {
                    Log.w(TAG, "TTS init failed with status $status")
                }
            }
        }, "TTS-Init").start()
    }

    /** Speak a short phrase: Almost (severity < 3) → "Attention !", Hit (severity >= 3) → "AYOYE !". */
    fun speakHit(severity: Int) {
        if (!ready) return
        val phrase = if (severity >= 3) "AYOYE !" else "Attention !"
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "hit_${System.currentTimeMillis()}")
    }

    /** Speak an arbitrary phrase in French. */
    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
