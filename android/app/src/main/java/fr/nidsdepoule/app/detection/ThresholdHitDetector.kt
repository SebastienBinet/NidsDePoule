package fr.nidsdepoule.app.detection

/**
 * Acceleration reading stored in the circular buffer.
 * Uses magnitude (orientation-independent) from TYPE_LINEAR_ACCELERATION.
 */
data class AccelReading(
    val timestamp: Long,
    val magnitudeMg: Int,
)

/**
 * Circular buffer that records accelerometer readings.
 *
 * Used to capture the last N seconds of acceleration data when the user
 * presses the "Hit" button. No auto-detection — just buffering.
 */
class AccelRecorder(
    private val bufferSize: Int = 1500,
) {
    private val buffer = mutableListOf<AccelReading>()

    /** Buffer an accelerometer reading. Call at ~50 Hz. */
    fun addReading(timestamp: Long, magnitudeMg: Int) {
        buffer.add(AccelReading(timestamp, magnitudeMg))
        if (buffer.size > bufferSize) {
            buffer.removeAt(0)
        }
    }

    /** Return the last [durationMs] worth of readings. */
    fun recentReadings(durationMs: Long = 5000): List<AccelReading> {
        if (buffer.isEmpty()) return emptyList()
        val cutoff = buffer.last().timestamp - durationMs
        return buffer.filter { it.timestamp >= cutoff }
    }

    fun reset() {
        buffer.clear()
    }
}
