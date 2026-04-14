package fr.nidsdepoule.capture.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Registers accelerometer, gyroscope, and magnetometer at SENSOR_DELAY_FASTEST (0 us).
 * Each sensor gets its own listener to avoid lock contention.
 *
 * On Android 12+, the HIGH_SAMPLING_RATE_SENSORS permission is required to get rates above 200 Hz.
 */
class SensorCollector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var accelListener: SensorEventListener? = null
    private var linAccelListener: SensorEventListener? = null
    private var gyroListener: SensorEventListener? = null
    private var magListener: SensorEventListener? = null

    /** Returns hardware metadata for all available sensors. */
    fun getSensorInfos(): Map<String, SensorInfo> {
        val infos = mutableMapOf<String, SensorInfo>()
        accel?.let {
            infos["accelerometer"] = SensorInfo(it.name, it.vendor, it.resolution, it.maximumRange, 0)
        }
        linAccel?.let {
            infos["linear_acceleration"] = SensorInfo(it.name, it.vendor, it.resolution, it.maximumRange, 0)
        }
        gyro?.let {
            infos["gyroscope"] = SensorInfo(it.name, it.vendor, it.resolution, it.maximumRange, 0)
        }
        mag?.let {
            infos["magnetometer"] = SensorInfo(it.name, it.vendor, it.resolution, it.maximumRange, 0)
        }
        return infos
    }

    fun start(
        onAccel: (timestampNs: Long, x: Float, y: Float, z: Float) -> Unit,
        onLinAccel: (timestampNs: Long, x: Float, y: Float, z: Float) -> Unit,
        onGyro: (timestampNs: Long, x: Float, y: Float, z: Float) -> Unit,
        onMag: (timestampNs: Long, x: Float, y: Float, z: Float) -> Unit,
    ) {
        accel?.let { sensor ->
            accelListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onAccel(event.timestamp, event.values[0], event.values[1], event.values[2])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        linAccel?.let { sensor ->
            linAccelListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onLinAccel(event.timestamp, event.values[0], event.values[1], event.values[2])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(linAccelListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        gyro?.let { sensor ->
            gyroListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onGyro(event.timestamp, event.values[0], event.values[1], event.values[2])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        mag?.let { sensor ->
            magListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onMag(event.timestamp, event.values[0], event.values[1], event.values[2])
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(magListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() {
        accelListener?.let { sensorManager.unregisterListener(it) }
        linAccelListener?.let { sensorManager.unregisterListener(it) }
        gyroListener?.let { sensorManager.unregisterListener(it) }
        magListener?.let { sensorManager.unregisterListener(it) }
        accelListener = null
        linAccelListener = null
        gyroListener = null
        magListener = null
    }
}
