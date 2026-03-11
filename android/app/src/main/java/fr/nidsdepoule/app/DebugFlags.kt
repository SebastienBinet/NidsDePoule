package fr.nidsdepoule.app

/**
 * Temporary debug flags to isolate ANR cause.
 * Set each to true to DISABLE that subsystem.
 * Re-enable one at a time to find the blocker.
 */
object DebugFlags {
    /** Disable the osmdroid MapView widget entirely */
    const val DISABLE_MAP = false
    /** Disable accelerometer sensor registration */
    const val DISABLE_ACCELEROMETER = false
    /** Disable GPS / location source */
    const val DISABLE_LOCATION = false
    /** Disable the foreground DetectionService */
    const val DISABLE_SERVICE = false
    /** Disable voice command listener (audio capture + MFCC) */
    const val DISABLE_VOICE = false
    /** Disable TTS (text-to-speech) initialization */
    const val DISABLE_TTS = false
    /** Disable network connectivity check */
    const val DISABLE_NETWORK = false
}
