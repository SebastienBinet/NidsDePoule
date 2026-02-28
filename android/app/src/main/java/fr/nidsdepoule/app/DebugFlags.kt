package fr.nidsdepoule.app

/**
 * Temporary debug flags to isolate ANR cause.
 * Set each to true to DISABLE that subsystem.
 * Re-enable one at a time to find the blocker.
 */
object DebugFlags {
    /** Disable the osmdroid MapView widget entirely */
    const val DISABLE_MAP = true
    /** Disable accelerometer sensor registration */
    const val DISABLE_ACCELEROMETER = false
    /** Disable GPS / location source */
    const val DISABLE_LOCATION = false
    /** Disable the foreground DetectionService */
    const val DISABLE_SERVICE = true
    /** Disable voice command listener (audio capture + MFCC) */
    const val DISABLE_VOICE = true
    /** Disable TTS (text-to-speech) initialization */
    const val DISABLE_TTS = true
    /** Disable network connectivity check */
    const val DISABLE_NETWORK = false
}
