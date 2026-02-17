package fr.nidsdepoule.app.reporting

/**
 * Tracks data sent to the server over sliding time windows.
 *
 * Provides:
 * - KB sent in the last minute
 * - MB sent in the last hour
 * - MB sent in the current month
 *
 * Pure Kotlin — no Android dependencies. Testable on JVM.
 * The month counter must be persisted externally (SharedPreferences in the app).
 */
class DataUsageTracker(
    /** Provider for current time in milliseconds. Inject for testing. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    // Ring buffer of (timestampMs, bytes) for sliding windows
    private val entries = mutableListOf<Pair<Long, Int>>()

    // Persistent month counter (caller is responsible for loading/saving)
    var monthBytes: Long = 0L
        private set
    var monthStartMs: Long = 0L

    /**
     * Record that [bytes] were sent at the current time.
     */
    fun record(bytes: Int) {
        val now = nowMs()
        entries.add(now to bytes)
        monthBytes += bytes

        // Prune entries older than 1 hour (we don't need them)
        val oneHourAgo = now - 3_600_000
        entries.removeAll { it.first < oneHourAgo }
    }

    /** Bytes sent in the last 60 seconds. */
    fun bytesLastMinute(): Long {
        val cutoff = nowMs() - 60_000
        return entries.filter { it.first >= cutoff }.sumOf { it.second.toLong() }
    }

    /** Bytes sent in the last 60 minutes. */
    fun bytesLastHour(): Long {
        val cutoff = nowMs() - 3_600_000
        return entries.filter { it.first >= cutoff }.sumOf { it.second.toLong() }
    }

    /** KB sent in the last minute (for UI display). */
    fun kbLastMinute(): Float = bytesLastMinute() / 1024f

    /** MB sent in the last hour (for UI display). */
    fun mbLastHour(): Float = bytesLastHour() / (1024f * 1024f)

    /** MB sent this month (for UI display). */
    fun mbThisMonth(): Float = monthBytes / (1024f * 1024f)

    /**
     * Reset the month counter. Call on the 1st of each month
     * or when the tracked month changes.
     */
    fun resetMonth() {
        monthBytes = 0L
        monthStartMs = nowMs()
    }

    /**
     * Restore persisted month state. Call on app startup.
     */
    fun restoreMonth(bytes: Long, startMs: Long) {
        monthBytes = bytes
        monthStartMs = startMs
    }
}
