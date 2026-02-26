package fr.nidsdepoule.app.detection

/**
 * How the pothole report was generated.
 *
 * The string values are sent to the server in the JSON "source" field.
 */
enum class ReportSource(val wire: String) {
    /** "iiiiiiiii !!!" — there's a pothole near me (visual sighting). */
    ALMOST("almost"),

    /** "AYOYE !?!#$!" — I just hit a pothole (impact with accel capture). */
    HIT("hit"),
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
    val source: ReportSource = ReportSource.HIT,
)
