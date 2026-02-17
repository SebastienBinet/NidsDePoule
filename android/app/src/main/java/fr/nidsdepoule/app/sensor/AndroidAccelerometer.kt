package fr.nidsdepoule.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Android platform adapter for the accelerometer.
 * Reads the TYPE_ACCELEROMETER sensor at SENSOR_DELAY_GAME (~50Hz).
 * Converts m/s² to milli-g (1g = 9.81 m/s² ≈ 1000 mg).
 */
class AndroidAccelerometer(context: Context) : AccelerometerSource {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listener: SensorEventListener? = null

    override fun start(callback: AccelerometerCallback) {
        if (accelerometer == null) return

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Android accelerometer returns m/s². Convert to milli-g.
                // 1g = 9.80665 m/s²  →  mg = (m/s²) / 9.80665 * 1000
                val factor = 1000.0f / 9.80665f
                val x = (event.values[0] * factor).toInt()
                val y = (event.values[1] * factor).toInt()
                val z = (event.values[2] * factor).toInt()
                val timestampMs = event.timestamp / 1_000_000  // nano → ms
                callback.onReading(timestampMs, x, y, z)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME  // ~50Hz
        )
    }

    override fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
    }
}
