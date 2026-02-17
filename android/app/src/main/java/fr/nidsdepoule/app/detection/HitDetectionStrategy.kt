package fr.nidsdepoule.app.detection

data class HitEvent(
    val timestampMs: Long,
    val peakVerticalMg: Int,
    val peakLateralMg: Int,
    val durationMs: Int,
    val severity: Int,
    val waveformVertical: List<Int>,
    val waveformLateral: List<Int>,
    val baselineMg: Int,
    val peakToBaselineRatio: Int,
)

interface HitDetectionStrategy {
    fun processReading(timestamp: Long, verticalAccelMg: Int, lateralAccelMg: Int, speedMps: Float): HitEvent?
    fun reset()
}
