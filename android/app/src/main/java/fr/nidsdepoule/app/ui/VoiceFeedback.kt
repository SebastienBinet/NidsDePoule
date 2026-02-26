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
class VoiceFeedback(context: Context) {

    companion object {
        private const val TAG = "VoiceFeedback"
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CANADA_FRENCH)
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fall back to generic French
                    tts?.setLanguage(Locale.FRENCH)
                }
                tts?.setSpeechRate(1.3f)  // slightly faster for urgency
                ready = true
                Log.i(TAG, "TTS initialized in French")
            } else {
                Log.w(TAG, "TTS init failed with status $status")
            }
        }
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
