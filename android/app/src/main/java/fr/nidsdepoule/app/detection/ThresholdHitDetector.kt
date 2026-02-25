package fr.nidsdepoule.app.detection

/**
 * Acceleration reading stored in the detection buffer.
 * Uses magnitude (orientation-independent) from TYPE_LINEAR_ACCELERATION.
 */
data class AccelReading(
    val timestamp: Long,
    val magnitudeMg: Int,
)

class ThresholdHitDetector(
    private val bufferSize: Int = 1500,
    thresholdFactor: Double = DEFAULT_THRESHOLD_FACTOR,
    minMagnitudeMg: Int = DEFAULT_MIN_MAGNITUDE_MG,
    private val waveformSamples: Int = 150,
    private val cooldownMs: Long = 500
) : HitDetectionStrategy {

    /** Relative multiplier: a reading must exceed baseline × this factor to trigger. */
    var thresholdFactor: Double = thresholdFactor

    /** Absolute floor in milli-g: a reading must also exceed this value to trigger. */
    var minMagnitudeMg: Int = minMagnitudeMg

    companion object {
        const val DEFAULT_THRESHOLD_FACTOR = 3.0
        const val DEFAULT_MIN_MAGNITUDE_MG = 150
    }

    private val buffer = mutableListOf<AccelReading>()
    private var lastHitTimestamp: Long = 0

    override fun processReading(
        timestamp: Long,
        magnitudeMg: Int,
        speedMps: Float
    ): HitEvent? {
        // Add reading to circular buffer
        buffer.add(AccelReading(timestamp, magnitudeMg))
        if (buffer.size > bufferSize) {
            buffer.removeAt(0)
        }

        // Need enough samples to compute baseline
        if (buffer.size < 10) {
            return null
        }

        // Check cooldown
        if (timestamp - lastHitTimestamp < cooldownMs) {
            return null
        }

        // Compute baseline as rolling median of magnitude
        val baseline = computeBaseline()

        // Detect hit when current magnitude exceeds BOTH:
        // 1. A relative threshold (baseline × factor) — adapts to road conditions
        // 2. An absolute minimum floor — prevents false positives from noise
        val threshold = baseline * thresholdFactor

        if (magnitudeMg < threshold || magnitudeMg < minMagnitudeMg) {
            return null
        }

        // Hit detected - mark timestamp for cooldown
        lastHitTimestamp = timestamp

        // Find peak in recent window
        val peakIndex = findPeakIndex()
        val peakReading = buffer[peakIndex]
        val peakMagnitude = peakReading.magnitudeMg

        // Extract waveform centered on peak
        val waveform = extractWaveform(peakIndex)

        // Classify severity
        val ratio = peakMagnitude.toDouble() / baseline
        val severity = when {
            ratio < 3.0 -> 1  // light
            ratio < 5.0 -> 2  // medium
            else -> 3         // heavy
        }

        // Estimate duration (time span where magnitude exceeds baseline * 1.5)
        val durationMs = estimateDuration(baseline)

        return HitEvent(
            timestampMs = peakReading.timestamp,
            peakVerticalMg = peakMagnitude,  // magnitude sent as "vertical" for wire compat
            peakLateralMg = 0,
            durationMs = durationMs,
            severity = severity,
            waveformVertical = waveform,     // magnitude waveform sent as "vertical"
            waveformLateral = emptyList(),
            baselineMg = baseline,
            peakToBaselineRatio = (ratio * 100).toInt()
        )
    }

    /** Current rolling baseline (median magnitude), or 0 if not enough samples. */
    val currentBaselineMg: Int
        get() = if (buffer.size >= 10) computeBaseline() else 0

    override fun reset() {
        buffer.clear()
        lastHitTimestamp = 0
    }

    override fun recentReadings(durationMs: Long): List<AccelReading> {
        if (buffer.isEmpty()) return emptyList()
        val cutoff = buffer.last().timestamp - durationMs
        return buffer.filter { it.timestamp >= cutoff }
    }

    private fun computeBaseline(): Int {
        val values = buffer.map { it.magnitudeMg }.sorted()
        return values[values.size / 2]
    }

    private fun findPeakIndex(): Int {
        // Look for peak in recent readings (last 50 samples or entire buffer)
        val searchWindow = minOf(50, buffer.size)
        val startIdx = buffer.size - searchWindow

        var peakIdx = startIdx
        var peakValue = buffer[startIdx].magnitudeMg

        for (i in startIdx until buffer.size) {
            if (buffer[i].magnitudeMg > peakValue) {
                peakValue = buffer[i].magnitudeMg
                peakIdx = i
            }
        }

        return peakIdx
    }

    private fun extractWaveform(peakIndex: Int): List<Int> {
        val halfWindow = waveformSamples / 2
        val startIdx = maxOf(0, peakIndex - halfWindow)
        val endIdx = minOf(buffer.size, peakIndex + halfWindow)

        val waveform = mutableListOf<Int>()

        // Pad with zeros if needed at start
        val padStart = maxOf(0, halfWindow - peakIndex)
        repeat(padStart) { waveform.add(0) }

        // Extract actual samples
        for (i in startIdx until endIdx) {
            waveform.add(buffer[i].magnitudeMg)
        }

        // Pad with zeros if needed at end
        val padEnd = waveformSamples - waveform.size
        repeat(padEnd) { waveform.add(0) }

        return waveform
    }

    private fun estimateDuration(baseline: Int): Int {
        if (buffer.size < 2) return 0

        val threshold = baseline * 1.5
        var startIdx = -1
        var endIdx = -1

        // Search backwards from end for the elevated region
        for (i in buffer.size - 1 downTo 0) {
            if (buffer[i].magnitudeMg > threshold) {
                endIdx = i
                break
            }
        }

        if (endIdx == -1) return 0

        // Find start of elevated region
        for (i in endIdx downTo 0) {
            if (buffer[i].magnitudeMg <= threshold) {
                startIdx = i + 1
                break
            }
        }

        if (startIdx == -1) startIdx = 0

        return ((buffer[endIdx].timestamp - buffer[startIdx].timestamp)).toInt()
    }
}
