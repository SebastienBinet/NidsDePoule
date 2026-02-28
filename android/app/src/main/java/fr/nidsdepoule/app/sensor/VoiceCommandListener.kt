package fr.nidsdepoule.app.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.nidsdepoule.app.sensor.audio.AudioCapture
import fr.nidsdepoule.app.sensor.audio.DtwMatcher
import fr.nidsdepoule.app.sensor.audio.MfccExtractor
import fr.nidsdepoule.app.sensor.audio.VoiceProfileStore

/**
 * Continuous voice command listener for hands-free pothole reporting.
 *
 * Uses raw audio capture + MFCC feature extraction + DTW matching
 * against user-recorded templates. No speech-to-text — works with
 * exclamations, non-words, and any language.
 *
 * Listens for spoken keywords and fires callbacks:
 *   - "Almost" sounds → onAlmost  (e.g. "iiiii", "attention", "ouf")
 *   - "Hit" sounds    → onHit     (e.g. "ayoye", "ouch", "merde")
 */
class VoiceCommandListener(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandListener"

        /** DTW distance threshold — below this = match. */
        private const val MATCH_THRESHOLD = 45f

        /** Minimum time between triggers to avoid double-fires. */
        private const val COOLDOWN_MS = 2000L

        // Keywords that trigger an "Almost" report (seeing a pothole ahead).
        val ALMOST_KEYWORDS = listOf(
            "attention", "trou", "regarde", "ouf", "iiiii", "il y en a",
        )

        // Keywords that trigger a "Hit" report (just drove over one).
        val HIT_KEYWORDS = listOf(
            "ayoye", "ouch", "aille", "merde", "shit", "bang",
        )

        val ALL_KEYWORDS: List<String> = ALMOST_KEYWORDS + HIT_KEYWORDS
    }

    /** Callback when an "Almost" voice command is detected. */
    var onAlmost: (() -> Unit)? = null

    /** Callback when a "Hit" voice command is detected. */
    var onHit: (() -> Unit)? = null

    /** Observable state for the UI — true when microphone is actively listening. */
    var isListening by mutableStateOf(false)
        private set

    /**
     * Dev-mode observable: real-time match scores for each keyword.
     * Maps keyword name → similarity score in [0, 1]. Updated on every speech segment.
     */
    val matchScores = mutableStateMapOf<String, Float>()

    private val audioCapture = AudioCapture()
    private val mfccExtractor = MfccExtractor()
    private val profileStore = VoiceProfileStore(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Loaded profiles: keyword → list of MFCC templates. */
    private var profiles: Map<String, List<Array<FloatArray>>> = emptyMap()
    private var running = false
    private var lastTriggerMs = 0L

    /** Whether any voice profiles have been trained. */
    val hasProfiles: Boolean get() = profiles.isNotEmpty()

    /**
     * Start continuous listening. Requires RECORD_AUDIO permission to already be granted.
     */
    fun start() {
        if (running) return
        running = true

        // Initialize match scores on main thread (Compose state)
        for (kw in ALL_KEYWORDS) {
            matchScores[kw] = 0f
        }

        // Load profiles and start audio capture off the main thread
        // to avoid ANR (file I/O + audio hardware init).
        Thread({
            profileStore.migrateIfNeeded()
            reloadProfiles()

            audioCapture.onSpeechSegment = { segment ->
                processSpeechSegment(segment)
            }

            audioCapture.start()

            mainHandler.post {
                isListening = audioCapture.isRunning
                Log.i(TAG, "Voice listener started with ${profiles.size} trained keywords")
            }
        }, "VoiceListenerInit").start()
    }

    fun stop() {
        running = false
        audioCapture.stop()
        isListening = false
    }

    /** Reload profiles from disk (call after training). */
    fun reloadProfiles() {
        profiles = profileStore.loadAllProfiles()
        Log.d(TAG, "Loaded ${profiles.size} keyword profiles: ${profiles.keys}")
    }

    /** Get the profile store for training. */
    fun getProfileStore(): VoiceProfileStore = profileStore

    /** Get the MFCC extractor (shared with training). */
    fun getMfccExtractor(): MfccExtractor = mfccExtractor

    /**
     * Process a detected speech segment: extract MFCC, match against profiles.
     */
    private fun processSpeechSegment(pcm: ShortArray) {
        if (profiles.isEmpty()) return

        val mfcc = mfccExtractor.extract(pcm)
        if (mfcc.isEmpty()) return

        // Compute DTW distances for all trained keywords
        val distances = DtwMatcher.matchAll(mfcc, profiles)

        // Update match scores for dev overlay (on main thread)
        val scores = mutableMapOf<String, Float>()
        for (kw in ALL_KEYWORDS) {
            val dist = distances[kw] ?: Float.MAX_VALUE
            scores[kw] = DtwMatcher.similarity(dist, MATCH_THRESHOLD)
        }

        mainHandler.post {
            for ((kw, score) in scores) {
                matchScores[kw] = score
            }
        }

        // Find the best matching keyword
        val bestKw = distances.minByOrNull { it.value } ?: return
        val bestDist = bestKw.value
        val bestName = bestKw.key

        Log.d(TAG, "Best match: '$bestName' (dist=${"%.1f".format(bestDist)}, " +
                "threshold=$MATCH_THRESHOLD)")

        if (bestDist < MATCH_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs < COOLDOWN_MS) {
                Log.d(TAG, "Cooldown — ignoring match")
                return
            }
            lastTriggerMs = now

            Log.i(TAG, "Voice command: '$bestName' (dist=${"%.1f".format(bestDist)})")

            mainHandler.post {
                if (bestName in ALMOST_KEYWORDS) {
                    onAlmost?.invoke()
                } else if (bestName in HIT_KEYWORDS) {
                    onHit?.invoke()
                }
            }
        }
    }
}
