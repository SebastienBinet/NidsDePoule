package fr.nidsdepoule.app.ui

/**
 * Circular buffer that holds the last N acceleration samples for the graph.
 * Pure Kotlin — no Android dependencies.
 *
 * Holds 60 seconds of data at 50Hz = 3000 samples.
 * The UI samples this at a lower rate (e.g., every 4th point = ~750 points on screen).
 */
class AccelerationBuffer(private val maxSize: Int = 3000) {

    data class Sample(
        val timestampMs: Long,
        val verticalMg: Int,
        val lateralMg: Int,
        val isHit: Boolean = false,
    )

    private val samples = ArrayDeque<Sample>(maxSize)

    val size: Int get() = samples.size

    fun add(timestampMs: Long, verticalMg: Int, lateralMg: Int) {
        if (samples.size >= maxSize) {
            samples.removeFirst()
        }
        samples.addLast(Sample(timestampMs, verticalMg, lateralMg))
    }

    /**
     * Return a snapshot of samples, downsampled by [step] for rendering.
     * Returns a list of (index, verticalMg, lateralMg) triples.
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

    fun clear() {
        samples.clear()
    }
}
