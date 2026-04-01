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

    /** Automatic detection from accelerometer analysis. */
    AUTO("auto"),
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
    /** Index of the peak sample within waveformVertical (for display marking). */
    val peakIndex: Int = -1,
    /** What triggered auto-detection (null for manual). */
    val detectionReason: String? = null,
)

/** Phone holding mode, detected from accelerometer stability. */
enum class MountType {
    /** Phone in car mount — low baseline stddev. */
    CAR_MOUNT,
    /** Phone handheld — high baseline stddev. */
    HANDHELD,
    /** Not enough data to determine. */
    UNKNOWN,
}

/** Vehicle movement state, detected from GPS speed. */
enum class MovingType {
    /** Moving at driving speed (>2 m/s for >5s). */
    DRIVING,
    /** Just parked (speed dropped from >5 to <1 m/s within 60s). */
    JUST_PARKED,
    /** Not in a car (speed <0.5 m/s for >120s, no vehicle vibration). */
    NOT_IN_CAR,
    /** Not enough data. */
    UNKNOWN,
}

/** Data usage budget, user-configured. */
enum class DataUsageMode(val label: String) {
    WIFI_ONLY("WiFi"),
    MB_10("10M"),
    MB_100("100M"),
    GB_1("1G"),
    UNLIMITED("UNL"),
}
