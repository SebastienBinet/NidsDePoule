package fr.nidsdepoule.app.sensor

/**
 * Abstraction over the Android accelerometer.
 * The core detection module depends on this interface, not on android.hardware.*.
 */
interface AccelerometerSource {
    fun start(callback: AccelerometerCallback)
    fun stop()
}

fun interface AccelerometerCallback {
    /**
     * Called on each sensor reading.
     * @param timestamp monotonic timestamp in milliseconds
     * @param x lateral acceleration in milli-g (positive = right)
     * @param y forward acceleration in milli-g (positive = forward)
     * @param z vertical acceleration in milli-g (positive = up, ~1000 at rest)
     */
    fun onReading(timestamp: Long, x: Int, y: Int, z: Int)
}
