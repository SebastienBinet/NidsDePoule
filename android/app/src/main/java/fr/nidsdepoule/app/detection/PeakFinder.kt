package fr.nidsdepoule.app.detection

/**
 * Result of a peak detection.
 */
data class PeakResult(
    val index: Int,
    val timestamp: Long,
    val magnitudeMg: Int,
    /** Prominence: how much higher than the local baseline (running median). */
    val prominenceMg: Int,
)

/**
 * Multi-peak detection with exclusion of already-reported peaks.
 *
 * Algorithm:
 *   1. Compute running median over 1s window as local baseline
 *   2. Find local maxima (higher than both neighbors)
 *   3. Filter: prominence >= minProminence above local baseline
 *   4. Exclude peaks within exclusionMs of any timestamp in excludeTimestamps
 *   5. Merge: if two peaks < minSeparationMs apart, keep the taller one
 *   6. Return list sorted by magnitude (descending)
 */
object PeakFinder {

    /**
     * Find peaks in a list of accelerometer readings.
     *
     * @param readings The accelerometer data to search (must be time-ordered).
     * @param minProminenceMg Minimum prominence above local baseline to qualify as a peak.
     * @param minSeparationMs Minimum time between two peaks — closer peaks are merged (keep taller).
     * @param excludeTimestamps Set of timestamps to exclude (already-reported peaks).
     * @param exclusionMs Tolerance around excluded timestamps (default 100ms).
     * @return Peaks sorted by magnitude descending (tallest first).
     */
    fun findPeaks(
        readings: List<AccelReading>,
        minProminenceMg: Int = 100,
        minSeparationMs: Long = 500,
        excludeTimestamps: Set<Long> = emptySet(),
        exclusionMs: Long = 100,
    ): List<PeakResult> {
        if (readings.size < 3) return emptyList()

        // Step 1: Compute running median baseline (1s window = ~50 samples at 50Hz)
        val windowSize = 50
        val baselines = computeRunningMedian(readings, windowSize)

        // Step 2: Find local maxima
        val candidates = mutableListOf<PeakResult>()
        for (i in 1 until readings.size - 1) {
            val r = readings[i]
            if (r.magnitudeMg > readings[i - 1].magnitudeMg &&
                r.magnitudeMg > readings[i + 1].magnitudeMg
            ) {
                val prominence = r.magnitudeMg - baselines[i]
                if (prominence >= minProminenceMg) {
                    candidates.add(
                        PeakResult(
                            index = i,
                            timestamp = r.timestamp,
                            magnitudeMg = r.magnitudeMg,
                            prominenceMg = prominence,
                        )
                    )
                }
            }
        }

        // Step 3: Exclude peaks near already-reported timestamps
        val filtered = if (excludeTimestamps.isEmpty()) {
            candidates
        } else {
            candidates.filter { peak ->
                excludeTimestamps.none { excluded ->
                    kotlin.math.abs(peak.timestamp - excluded) <= exclusionMs
                }
            }
        }

        // Step 4: Merge peaks that are too close — keep the taller one
        val merged = mergePeaks(filtered, minSeparationMs)

        // Step 5: Sort by magnitude descending
        return merged.sortedByDescending { it.magnitudeMg }
    }

    /**
     * Compute running median of magnitude values over a sliding window.
     * Returns an array of the same size as readings.
     */
    private fun computeRunningMedian(readings: List<AccelReading>, windowSize: Int): IntArray {
        val n = readings.size
        val result = IntArray(n)
        val halfWindow = windowSize / 2

        for (i in readings.indices) {
            val start = maxOf(0, i - halfWindow)
            val end = minOf(n, i + halfWindow + 1)
            val window = IntArray(end - start)
            for (j in start until end) {
                window[j - start] = readings[j].magnitudeMg
            }
            window.sort()
            result[i] = window[window.size / 2]
        }
        return result
    }

    /**
     * Merge peaks that are closer than minSeparationMs, keeping the taller one.
     */
    private fun mergePeaks(peaks: List<PeakResult>, minSeparationMs: Long): List<PeakResult> {
        if (peaks.isEmpty()) return emptyList()
        val sorted = peaks.sortedBy { it.timestamp }
        val result = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val last = result.last()
            if (current.timestamp - last.timestamp < minSeparationMs) {
                // Keep the taller one
                if (current.magnitudeMg > last.magnitudeMg) {
                    result[result.lastIndex] = current
                }
            } else {
                result.add(current)
            }
        }
        return result
    }
}
