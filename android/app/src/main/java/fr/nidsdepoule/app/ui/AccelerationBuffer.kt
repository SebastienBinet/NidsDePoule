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

    fun clear() {
        samples.clear()
    }
}
