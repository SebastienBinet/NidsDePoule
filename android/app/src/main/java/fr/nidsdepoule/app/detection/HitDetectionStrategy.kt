package fr.nidsdepoule.app.detection

/**
 * How the pothole report was generated.
 *
 * The string values are sent to the server in the JSON "source" field.
 */
enum class ReportSource(val wire: String) {
    /** Automatic accelerometer threshold detection. */
    AUTO("auto"),

    /** User visually spotted a small/medium pothole ("iiii !"). */
    VISUAL_SMALL("visual_small"),

    /** User visually spotted a big pothole ("iiiiiiiii !!!"). */
    VISUAL_BIG("visual_big"),

    /** User just hit a small/medium pothole ("Ouch !") — accelerometer captured. */
    IMPACT_SMALL("impact_small"),

    /** User just hit a big pothole ("AYOYE !?!#$!") — accelerometer captured. */
    IMPACT_BIG("impact_big"),
}

data class HitEvent(
    val timestampMs: Long,
    val peakVerticalMg: Int,
    val peakLateralMg: Int,
    val durationMs: Int,
    val severity: Int,
    val waveformVertical: List<Int>,
    val waveformLateral: List<Int>,
    val baselineMg: Int,
    val peakToBaselineRatio: Int,
    val source: ReportSource = ReportSource.AUTO,
)

interface HitDetectionStrategy {
    /**
     * Process an acceleration reading.
     * @param timestamp monotonic timestamp in ms
     * @param magnitudeMg acceleration magnitude in milli-g (orientation-independent)
     * @param speedMps current GPS speed
     */
    fun processReading(timestamp: Long, magnitudeMg: Int, speedMps: Float): HitEvent?
    fun reset()

    /** Return the last [durationMs] worth of accelerometer readings (for manual "Ouch"/"AYOYE" capture). */
    fun recentReadings(durationMs: Long = 5000): List<AccelReading>
}
