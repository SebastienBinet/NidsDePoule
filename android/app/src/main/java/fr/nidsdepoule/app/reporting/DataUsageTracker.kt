package fr.nidsdepoule.app.reporting

/**
 * Data transfer categories for per-category tracking.
 */
enum class DataCategory {
    /** Hit reports (app → server) and server responses. */
    HITS,
    /** Heartbeat pings (app → server). */
    HEARTBEAT,
    /** Pothole positions fetched from server. */
    POTHOLES,
    /** OSM map tiles downloaded. */
    TILES,
}

/**
 * Per-category byte counters (session-only, not persisted).
 */
data class CategoryBytes(
    var uploadBytes: Long = 0L,
    var downloadBytes: Long = 0L,
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
}

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

    // Per-category counters (session-only)
    private val _categories = mutableMapOf<DataCategory, CategoryBytes>()

    /** Record bytes uploaded and downloaded, optionally with a category. */
    fun record(bytesSent: Int, bytesReceived: Int = 0, category: DataCategory? = null) {
        weekUploadBytes += bytesSent
        weekDownloadBytes += bytesReceived
        monthUploadBytes += bytesSent
        monthDownloadBytes += bytesReceived
        if (category != null) {
            val cat = _categories.getOrPut(category) { CategoryBytes() }
            cat.uploadBytes += bytesSent
            cat.downloadBytes += bytesReceived
        }
    }

    /** Get per-category counters for detail display. */
    fun categorySnapshot(): Map<DataCategory, CategoryBytes> =
        _categories.toMap()

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
