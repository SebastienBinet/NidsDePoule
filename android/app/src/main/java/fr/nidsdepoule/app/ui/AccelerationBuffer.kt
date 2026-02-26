package fr.nidsdepoule.app.ui

/**
 * Circular buffer that holds the last N acceleration samples for the graph.
 * Pure Kotlin — no Android dependencies.
 *
 * Holds 30 seconds of data at 50Hz = 1500 samples.
 * Stores magnitude in milli-g (orientation-independent).
 * The UI samples this at a lower rate (e.g., every 4th point = ~375 points on screen).
 */
class AccelerationBuffer(private val maxSize: Int = 1500) {

    data class Sample(
        val timestampMs: Long,
        val magnitudeMg: Int,
        val isHit: Boolean = false,
        /** True if this sample is the peak acceleration sent to server for a Hit report. */
        val isPeakSent: Boolean = false,
    )

    private val samples = ArrayDeque<Sample>(maxSize)

    val size: Int get() = samples.size

    fun add(timestampMs: Long, magnitudeMg: Int) {
        if (samples.size >= maxSize) {
            samples.removeFirst()
        }
        samples.addLast(Sample(timestampMs, magnitudeMg))
    }

    /**
     * Return a snapshot of samples, downsampled by [step] for rendering.
     */
    fun snapshot(step: Int = 4): List<Sample> {
        val result = mutableListOf<Sample>()
        var i = 0
        for (s in samples) {
            if (i % step == 0) {
                result.add(s)
            }
            i++
        }
        return result
    }

    fun markLastAsHit() {
        if (samples.isNotEmpty()) {
            val last = samples.removeLast()
            samples.addLast(last.copy(isHit = true))
        }
    }

    /**
     * Find the sample with the highest acceleration in the entire buffer (last 30s),
     * mark it as isPeakSent=true and isHit=true, and return it.
     * Returns null if the buffer is empty.
     */
    fun findAndMarkPeak(): Sample? {
        if (samples.isEmpty()) return null

        var peakIdx = 0
        var peakMag = 0
        var idx = 0
        for (s in samples) {
            if (s.magnitudeMg > peakMag) {
                peakMag = s.magnitudeMg
                peakIdx = idx
            }
            idx++
        }

        // Replace the peak sample with the marked version
        val newSamples = ArrayList<Sample>(samples.size)
        idx = 0
        for (s in samples) {
            if (idx == peakIdx) {
                newSamples.add(s.copy(isHit = true, isPeakSent = true))
            } else {
                newSamples.add(s)
            }
            idx++
        }

        samples.clear()
        for (s in newSamples) samples.addLast(s)

        return newSamples[peakIdx]
    }

    fun clear() {
        samples.clear()
    }
}
