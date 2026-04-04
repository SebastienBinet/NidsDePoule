package fr.nidsdepoule.capture.sensor

/** Raw 3-axis sensor sample with boot-time nanosecond timestamp. */
data class SensorSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

/** Hardware metadata for a single sensor. */
data class SensorInfo(
    val name: String,
    val vendor: String,
    val resolution: Float,
    val maxRange: Float,
    val requestedDelayUs: Int,
)
