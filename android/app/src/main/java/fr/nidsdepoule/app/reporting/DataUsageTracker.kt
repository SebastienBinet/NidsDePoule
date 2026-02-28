package fr.nidsdepoule.app.reporting

/**
 * Tracks data uploaded and downloaded with weekly and monthly totals.
 *
 * Pure Kotlin — no Android dependencies. Testable on JVM.
 * The week/month counters must be persisted externally (SharedPreferences).
 */
class DataUsageTracker(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    // Weekly counters
    var weekUploadBytes: Long = 0L
        private set
    var weekDownloadBytes: Long = 0L
        private set
    var weekStartMs: Long = 0L

    // Monthly counters
    var monthUploadBytes: Long = 0L
        private set
    var monthDownloadBytes: Long = 0L
        private set
    var monthStartMs: Long = 0L

    /** Record bytes uploaded and downloaded. */
    fun record(bytesSent: Int, bytesReceived: Int = 0) {
        weekUploadBytes += bytesSent
        weekDownloadBytes += bytesReceived
        monthUploadBytes += bytesSent
        monthDownloadBytes += bytesReceived
    }

    // --- Display accessors ---

    fun mbUploadThisWeek(): Float = weekUploadBytes / (1024f * 1024f)
    fun mbDownloadThisWeek(): Float = weekDownloadBytes / (1024f * 1024f)
    fun mbUploadThisMonth(): Float = monthUploadBytes / (1024f * 1024f)
    fun mbDownloadThisMonth(): Float = monthDownloadBytes / (1024f * 1024f)

    // --- Legacy accessors (backward compat) ---

    val monthBytes: Long get() = monthUploadBytes

    fun resetWeek() {
        weekUploadBytes = 0L
        weekDownloadBytes = 0L
        weekStartMs = nowMs()
    }

    fun resetMonth() {
        monthUploadBytes = 0L
        monthDownloadBytes = 0L
        monthStartMs = nowMs()
    }

    /** Restore full state on app startup. */
    fun restore(
        weekUp: Long, weekDown: Long, weekStart: Long,
        monthUp: Long, monthDown: Long, monthStart: Long,
    ) {
        weekUploadBytes = weekUp
        weekDownloadBytes = weekDown
        weekStartMs = weekStart
        monthUploadBytes = monthUp
        monthDownloadBytes = monthDown
        monthStartMs = monthStart
    }

    /** Legacy restore (old month-only prefs). */
    fun restoreMonth(bytes: Long, startMs: Long) {
        monthUploadBytes = bytes
        monthStartMs = startMs
    }
}
