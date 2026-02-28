package fr.nidsdepoule.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Android platform adapter for the accelerometer.
 * Reads TYPE_LINEAR_ACCELERATION at SENSOR_DELAY_GAME (~50Hz).
 *
 * TYPE_LINEAR_ACCELERATION uses Android's sensor fusion to subtract gravity,
 * so the output is pure dynamic acceleration regardless of phone orientation.
 * At rest, all three axes read ~0 (not ~1g on the vertical axis).
 *
 * Converts m/s² to milli-g (1g = 9.80665 m/s² = 1000 mg).
 */
class AndroidAccelerometer(context: Context) : AccelerometerSource {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var listener: SensorEventListener? = null

    override fun start(callback: AccelerometerCallback) {
        if (linearAccel == null) return

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // TYPE_LINEAR_ACCELERATION returns m/s² with gravity already removed.
                // Convert to milli-g.
                val factor = 1000.0f / 9.80665f
                val x = (event.values[0] * factor).toInt()
                val y = (event.values[1] * factor).toInt()
                val z = (event.values[2] * factor).toInt()
                // Convert sensor boot-time nanos to wall-clock millis.
                // SensorEvent.timestamp is nanoseconds since boot, but
                // LocationReading uses System.currentTimeMillis(). Without
                // this conversion, interpolateLocation() can't match them.
                val sensorBootMs = event.timestamp / 1_000_000
                val bootMs = SystemClock.elapsedRealtime()
                val timestampMs = System.currentTimeMillis() - (bootMs - sensorBootMs)
                callback.onReading(timestampMs, x, y, z)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            listener,
            linearAccel,
            SensorManager.SENSOR_DELAY_GAME  // ~50Hz
        )
    }

    override fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
    }
}
