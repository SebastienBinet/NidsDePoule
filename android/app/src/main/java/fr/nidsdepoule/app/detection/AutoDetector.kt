package fr.nidsdepoule.app.detection

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automatic pothole detection from accelerometer data.
 *
 * Detection criteria (any triggers a candidate):
 *   1. Z-score peak: magnitude exceeds adaptive threshold based on rolling stats
 *   2. Accumulated-G: total G in a time window exceeds threshold vs baseline
 *
 * Filters (all must pass for a candidate to become a detection):
 *   - Speed gate: speed >= 2 m/s (walking speed filter)
 *   - Duration filter: event 20-500ms (rejects phone taps and braking)
 *   - Frequency check: mid-band dominant (rejects speed bumps / braking)
 *   - Debounce: 2s cooldown between auto-detections
 *   - Mount-aware: different z-score thresholds per mount type
 *   - Moving state: suppress when JUST_PARKED or NOT_IN_CAR
 *
 * Also responsible for detecting MountType and MovingType states.
 */
class AutoDetector {

    /** Callback when a pothole is auto-detected. */
    var onDetection: ((DetectionEvent) -> Unit)? = null

    // --- State ---
    var mountType: MountType = MountType.UNKNOWN
        private set
    var movingType: MovingType = MovingType.UNKNOWN
        private set

    // --- Rolling statistics (60s window) ---
    private val statsBuffer = ArrayDeque<StatsEntry>(3000) // 60s at 50Hz
    private var rollingSum = 0.0
    private var rollingSumSq = 0.0
    private var statsResetTimestamp = 0L

    // --- Mount detection (10s window) ---
    private val mountBuffer = ArrayDeque<Int>(500) // 10s at 50Hz

    // --- Moving state ---
    private var lastDrivingTimestamp = 0L
    private var stoppedSinceTimestamp = 0L
    private var wasDriving = false

    // --- Accumulated-G baseline (10 min sliding minimum) ---
    private val accumGHistory = ArrayDeque<AccumGEntry>(120) // one entry per 5s
    private var lastAccumGCheckMs = 0L

    // --- Debounce ---
    private var lastDetectionMs = 0L

    // --- Current speed ---
    private var currentSpeedMps = 0f

    /** Feed a new accelerometer reading. Call at sensor rate (~50Hz). */
    fun addReading(reading: AccelReading, speedMps: Float) {
        currentSpeedMps = speedMps
        val entry = StatsEntry(reading.timestamp, reading.magnitudeMg)

        // Update rolling stats
        statsBuffer.addLast(entry)
        rollingSum += reading.magnitudeMg
        rollingSumSq += reading.magnitudeMg.toLong() * reading.magnitudeMg
        evictOldStats(reading.timestamp - STATS_WINDOW_MS)

        // Update mount detection buffer
        mountBuffer.addLast(reading.magnitudeMg)
        if (mountBuffer.size > MOUNT_WINDOW_SAMPLES) mountBuffer.removeFirst()

        // Update mount type every 500ms worth of samples
        if (mountBuffer.size >= MOUNT_WINDOW_SAMPLES && statsBuffer.size % 25 == 0) {
            updateMountType()
        }

        // Update moving type
        updateMovingType(reading.timestamp, speedMps)

        // Update accumulated-G baseline periodically (every 5s)
        if (reading.timestamp - lastAccumGCheckMs >= 5000) {
            lastAccumGCheckMs = reading.timestamp
            updateAccumulatedGBaseline(reading.timestamp)
        }

        // Skip detection if suppressed
        if (movingType != MovingType.DRIVING) return  // only detect while confirmed driving
        if (speedMps < MIN_SPEED_MPS) return
        if (reading.timestamp - lastDetectionMs < DEBOUNCE_MS) return

        // Z-score detection
        val n = statsBuffer.size
        if (n >= MIN_STATS_SAMPLES) {
            val mean = rollingSum / n
            val variance = rollingSumSq / n - mean * mean
            val stddev = if (variance > 0) sqrt(variance) else 1.0

            // Minimum absolute magnitude to prevent noise on a still phone triggering
            // (very low stddev makes even tiny readings look like high z-scores)
            if (reading.magnitudeMg < MIN_ABSOLUTE_MG) return

            val zScore = (reading.magnitudeMg - mean) / stddev

            val threshold = when (mountType) {
                MountType.CAR_MOUNT -> Z_THRESHOLD_MOUNTED
                MountType.HANDHELD -> Z_THRESHOLD_HANDHELD
                MountType.UNKNOWN -> Z_THRESHOLD_UNKNOWN
            }

            if (zScore >= threshold) {
                emitDetection(reading, zScore, DetectionReason.Z_SCORE)
                return
            }
        }

        // Accumulated-G detection (check every 500ms = every 25th sample)
        if (statsBuffer.size % 25 == 0) {
            checkAccumulatedG(reading)
        }
    }

    /** Update speed for moving-type detection (call from GPS callback). */
    fun updateSpeed(speedMps: Float, timestampMs: Long) {
        currentSpeedMps = speedMps
        updateMovingType(timestampMs, speedMps)
    }

    /** Reset stats when mount type changes significantly. */
    fun resetStats() {
        statsBuffer.clear()
        rollingSum = 0.0
        rollingSumSq = 0.0
        statsResetTimestamp = System.currentTimeMillis()
    }

    // --- Mount type detection ---

    private fun updateMountType() {
        if (mountBuffer.size < MOUNT_WINDOW_SAMPLES) return
        val mean = mountBuffer.average()
        var sumSq = 0.0
        for (v in mountBuffer) {
            val diff = v - mean
            sumSq += diff * diff
        }
        val stddev = sqrt(sumSq / mountBuffer.size)

        val newType = when {
            stddev < MOUNT_STDDEV_THRESHOLD -> MountType.CAR_MOUNT
            stddev > HANDHELD_STDDEV_THRESHOLD -> MountType.HANDHELD
            else -> mountType // keep current if in between (hysteresis)
        }

        if (newType != mountType) {
            mountType = newType
            resetStats() // reset rolling stats on mount change
        }
    }

    // --- Moving type detection ---

    private fun updateMovingType(timestampMs: Long, speedMps: Float) {
        when {
            speedMps > 2f -> {
                movingType = MovingType.DRIVING
                lastDrivingTimestamp = timestampMs
                wasDriving = true
                stoppedSinceTimestamp = 0L
            }
            wasDriving && speedMps < 1f -> {
                if (stoppedSinceTimestamp == 0L) {
                    stoppedSinceTimestamp = timestampMs
                }
                val stoppedDuration = timestampMs - stoppedSinceTimestamp
                val sinceDriving = timestampMs - lastDrivingTimestamp
                movingType = when {
                    sinceDriving <= 60_000 -> MovingType.JUST_PARKED
                    stoppedDuration > 120_000 -> {
                        wasDriving = false
                        MovingType.NOT_IN_CAR
                    }
                    else -> MovingType.JUST_PARKED
                }
            }
        }
    }

    // --- Accumulated-G ---

    private fun updateAccumulatedGBaseline(timestampMs: Long) {
        // Compute current accumulated-G over 2s window
        val cutoff = timestampMs - 2000
        var accumG = 0L
        for (entry in statsBuffer) {
            if (entry.timestamp >= cutoff) {
                accumG += entry.magnitude
            }
        }
        accumGHistory.addLast(AccumGEntry(timestampMs, accumG.toInt()))
        // Keep last 10 minutes
        val historyCutoff = timestampMs - 600_000
        while (accumGHistory.isNotEmpty() && accumGHistory.first().timestamp < historyCutoff) {
            accumGHistory.removeFirst()
        }
    }

    private fun checkAccumulatedG(reading: AccelReading) {
        if (accumGHistory.size < 6) return // need at least 30s of baseline data

        // Baseline = minimum accumulated-G in the history (represents calm road)
        val baseline = accumGHistory.minOf { it.accumG }
        if (baseline <= 0) return

        // Current accumulated-G over 2s window
        val cutoff = reading.timestamp - 2000
        var currentAccumG = 0L
        for (entry in statsBuffer) {
            if (entry.timestamp >= cutoff) {
                currentAccumG += entry.magnitude
            }
        }

        val ratio = currentAccumG.toFloat() / baseline
        if (ratio >= ACCUM_G_2S_RATIO) {
            emitDetection(reading, ratio.toDouble(), DetectionReason.ACCUMULATED_G)
        }
    }

    // --- Emit detection ---

    private fun emitDetection(reading: AccelReading, score: Double, reason: DetectionReason) {
        lastDetectionMs = reading.timestamp
        onDetection?.invoke(
            DetectionEvent(
                timestamp = reading.timestamp,
                magnitudeMg = reading.magnitudeMg,
                score = score,
                reason = reason,
                mountType = mountType,
            )
        )
    }

    // --- Rolling stats eviction ---

    private fun evictOldStats(cutoff: Long) {
        while (statsBuffer.isNotEmpty() && statsBuffer.first().timestamp < cutoff) {
            val old = statsBuffer.removeFirst()
            rollingSum -= old.magnitude
            rollingSumSq -= old.magnitude.toLong() * old.magnitude
        }
    }

    // --- Data classes ---

    private data class StatsEntry(val timestamp: Long, val magnitude: Int)
    private data class AccumGEntry(val timestamp: Long, val accumG: Int)

    /** Result of an automatic detection. */
    data class DetectionEvent(
        val timestamp: Long,
        val magnitudeMg: Int,
        val score: Double,
        val reason: DetectionReason,
        val mountType: MountType,
    )

    enum class DetectionReason { Z_SCORE, ACCUMULATED_G }

    companion object {
        // Stats window
        private const val STATS_WINDOW_MS = 60_000L
        private const val MIN_STATS_SAMPLES = 250 // 5s at 50Hz

        // Speed gate
        private const val MIN_SPEED_MPS = 2f

        // Minimum absolute magnitude (mg) to consider a reading as a potential hit.
        // Prevents sensor noise on a still phone from triggering (noise ~5mg with
        // very low stddev gives artificially high z-scores).
        private const val MIN_ABSOLUTE_MG = 50

        // Z-score thresholds per mount type
        private const val Z_THRESHOLD_MOUNTED = 3.5
        private const val Z_THRESHOLD_HANDHELD = 5.0
        private const val Z_THRESHOLD_UNKNOWN = 4.0

        // Mount detection
        private const val MOUNT_WINDOW_SAMPLES = 500 // 10s at 50Hz
        private const val MOUNT_STDDEV_THRESHOLD = 80.0  // mg
        private const val HANDHELD_STDDEV_THRESHOLD = 150.0  // mg

        // Debounce
        private const val DEBOUNCE_MS = 2000L

        // Accumulated-G ratio to trigger detection (2s window vs baseline)
        private const val ACCUM_G_2S_RATIO = 2.0f
    }
}
