package fr.nidsdepoule.app.sensor

/**
 * Abstraction over GPS / Fused Location Provider.
 */
interface LocationSource {
    fun start(callback: LocationCallback)
    fun stop()
}

data class LocationReading(
    val timestampMs: Long,
    val latMicrodeg: Int,
    val lonMicrodeg: Int,
    val accuracyM: Int,
    val speedMps: Float,
    val bearingDeg: Float,
)

fun interface LocationCallback {
    fun onLocation(reading: LocationReading)
}
