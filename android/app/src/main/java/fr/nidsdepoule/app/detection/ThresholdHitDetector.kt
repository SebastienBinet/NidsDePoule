package fr.nidsdepoule.app.detection

import kotlin.math.abs

data class AccelReading(
    val timestamp: Long,
    val verticalMg: Int,
    val lateralMg: Int
)

class ThresholdHitDetector(
    private val bufferSize: Int = 3000,
    private val thresholdFactor: Double = 3.0,
    private val waveformSamples: Int = 150,
    private val cooldownMs: Long = 500
) : HitDetectionStrategy {

    private val buffer = mutableListOf<AccelReading>()
    private var lastHitTimestamp: Long = 0

    override fun processReading(
        timestamp: Long,
        verticalAccelMg: Int,
        lateralAccelMg: Int,
        speedMps: Float
    ): HitEvent? {
        // Add reading to circular buffer
        buffer.add(AccelReading(timestamp, verticalAccelMg, lateralAccelMg))
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

        // Compute baseline as rolling median of absolute vertical acceleration
        val baseline = computeBaseline()

        // Detect hit when current reading exceeds threshold
        val absVertical = abs(verticalAccelMg)
        val threshold = baseline * thresholdFactor

        if (absVertical < threshold) {
            return null
        }

        // Hit detected - mark timestamp for cooldown
        lastHitTimestamp = timestamp

        // Find peak in recent window
        val peakIndex = findPeakIndex()
        val peakReading = buffer[peakIndex]
        val peakAbsVertical = abs(peakReading.verticalMg)
        val peakAbsLateral = abs(peakReading.lateralMg)

        // Extract waveform centered on peak
        val (waveformVertical, waveformLateral) = extractWaveform(peakIndex)

        // Classify severity
        val ratio = peakAbsVertical.toDouble() / baseline
        val severity = when {
            ratio < 3.0 -> 1  // light
            ratio < 5.0 -> 2  // medium
            else -> 3         // heavy
        }

        // Estimate duration (time span where acceleration exceeds baseline * 1.5)
        val durationMs = estimateDuration(baseline)

        return HitEvent(
            timestampMs = peakReading.timestamp,
            peakVerticalMg = peakAbsVertical,
            peakLateralMg = peakAbsLateral,
            durationMs = durationMs,
            severity = severity,
            waveformVertical = waveformVertical,
            waveformLateral = waveformLateral,
            baselineMg = baseline,
            peakToBaselineRatio = (ratio * 100).toInt()
        )
    }

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
        val absoluteValues = buffer.map { abs(it.verticalMg) }.sorted()
        return absoluteValues[absoluteValues.size / 2]
    }

    private fun findPeakIndex(): Int {
        // Look for peak in recent readings (last 50 samples or entire buffer)
        val searchWindow = minOf(50, buffer.size)
        val startIdx = buffer.size - searchWindow

        var peakIdx = startIdx
        var peakValue = abs(buffer[startIdx].verticalMg)

        for (i in startIdx until buffer.size) {
            val absValue = abs(buffer[i].verticalMg)
            if (absValue > peakValue) {
                peakValue = absValue
                peakIdx = i
            }
        }

        return peakIdx
    }

    private fun extractWaveform(peakIndex: Int): Pair<List<Int>, List<Int>> {
        val halfWindow = waveformSamples / 2
        val startIdx = maxOf(0, peakIndex - halfWindow)
        val endIdx = minOf(buffer.size, peakIndex + halfWindow)

        val waveformVertical = mutableListOf<Int>()
        val waveformLateral = mutableListOf<Int>()

        // Pad with zeros if needed at start
        val padStart = maxOf(0, halfWindow - peakIndex)
        repeat(padStart) {
            waveformVertical.add(0)
            waveformLateral.add(0)
        }

        // Extract actual samples
        for (i in startIdx until endIdx) {
            waveformVertical.add(buffer[i].verticalMg)
            waveformLateral.add(buffer[i].lateralMg)
        }

        // Pad with zeros if needed at end
        val padEnd = waveformSamples - waveformVertical.size
        repeat(padEnd) {
            waveformVertical.add(0)
            waveformLateral.add(0)
        }

        return Pair(waveformVertical, waveformLateral)
    }

    private fun estimateDuration(baseline: Int): Int {
        if (buffer.size < 2) return 0

        val threshold = baseline * 1.5
        var startIdx = -1
        var endIdx = -1

        // Search backwards from end for the elevated region
        for (i in buffer.size - 1 downTo 0) {
            if (abs(buffer[i].verticalMg) > threshold) {
                endIdx = i
                break
            }
        }

        if (endIdx == -1) return 0

        // Find start of elevated region
        for (i in endIdx downTo 0) {
            if (abs(buffer[i].verticalMg) <= threshold) {
                startIdx = i + 1
                break
            }
        }

        if (startIdx == -1) startIdx = 0

        return ((buffer[endIdx].timestamp - buffer[startIdx].timestamp)).toInt()
    }
}
