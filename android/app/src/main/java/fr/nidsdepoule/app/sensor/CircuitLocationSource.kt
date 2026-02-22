package fr.nidsdepoule.app.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.*

/**
 * LocationSource that replays a circuit JSON file from assets.
 *
 * Emits LocationReadings at 1 Hz by interpolating between waypoints.
 * Loops continuously. At "stop" waypoints (suggested_speed_kmh == 0),
 * pauses for 2 seconds before continuing.
 */
class CircuitLocationSource(private val context: Context) : LocationSource {

    private data class Waypoint(
        val lat: Double,
        val lon: Double,
        val bearingDeg: Float,
        val suggestedSpeedKmh: Float,
        val isStop: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var callback: LocationCallback? = null
    private var waypoints: List<Waypoint> = emptyList()
    private var running = false

    // Current interpolation state
    private var segmentIndex = 0       // index of the waypoint we're traveling FROM
    private var segmentProgress = 0.0  // 0.0 = at waypoint[segmentIndex], 1.0 = at next
    private var stopTicksRemaining = 0 // countdown when stopped

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running || waypoints.size < 2) return
            emitCurrentPosition()
            advancePosition()
            handler.postDelayed(this, 1000L) // 1 Hz
        }
    }

    override fun start(callback: LocationCallback) {
        this.callback = callback
        loadCircuit()
        if (waypoints.size < 2) return

        segmentIndex = 0
        segmentProgress = 0.0
        stopTicksRemaining = 0
        running = true
        handler.post(tickRunnable)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        callback = null
    }

    private fun loadCircuit() {
        try {
            val json = context.assets.open("nddn_cemetery_loop.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.getJSONArray("waypoints")
            val list = mutableListOf<Waypoint>()
            for (i in 0 until arr.length()) {
                val wp = arr.getJSONObject(i)
                list.add(Waypoint(
                    lat = wp.getDouble("lat"),
                    lon = wp.getDouble("lon"),
                    bearingDeg = wp.getDouble("bearing_deg").toFloat(),
                    suggestedSpeedKmh = wp.getDouble("suggested_speed_kmh").toFloat(),
                    isStop = wp.getString("type") == "stop",
                ))
            }
            waypoints = list
        } catch (e: Exception) {
            waypoints = emptyList()
        }
    }

    private fun emitCurrentPosition() {
        val cb = callback ?: return
        val from = waypoints[segmentIndex]
        val to = waypoints[(segmentIndex + 1) % waypoints.size]
        val t = segmentProgress

        // Linear interpolation
        val lat = from.lat + (to.lat - from.lat) * t
        val lon = from.lon + (to.lon - from.lon) * t

        // Bearing from current segment
        val bearing = computeBearing(from.lat, from.lon, to.lat, to.lon)

        // Speed: use the "from" waypoint's suggested speed
        val speedMps = from.suggestedSpeedKmh / 3.6f

        cb.onLocation(LocationReading(
            timestampMs = System.currentTimeMillis(),
            latMicrodeg = (lat * 1_000_000).toInt(),
            lonMicrodeg = (lon * 1_000_000).toInt(),
            accuracyM = 3, // simulated GPS accuracy
            speedMps = speedMps,
            bearingDeg = bearing,
        ))
    }

    private fun advancePosition() {
        // If currently stopped, count down
        if (stopTicksRemaining > 0) {
            stopTicksRemaining--
            return
        }

        val from = waypoints[segmentIndex]
        val to = waypoints[(segmentIndex + 1) % waypoints.size]

        // Distance of the current segment
        val segmentDistM = haversineDistance(from.lat, from.lon, to.lat, to.lon)

        // How far we travel in 1 second at current speed
        val speedMps = from.suggestedSpeedKmh / 3.6f
        if (segmentDistM <= 0 || speedMps <= 0) {
            // Skip zero-length or zero-speed segments
            moveToNextSegment()
            return
        }

        val progressPerTick = speedMps / segmentDistM
        segmentProgress += progressPerTick

        // Crossed into next segment?
        while (segmentProgress >= 1.0) {
            segmentProgress -= 1.0
            moveToNextSegment()

            // If the new waypoint is a stop, pause
            val current = waypoints[segmentIndex]
            if (current.isStop) {
                stopTicksRemaining = 2  // 2 seconds pause
                segmentProgress = 0.0
                break
            }

            // Adjust progress for new segment distance
            val nextFrom = waypoints[segmentIndex]
            val nextTo = waypoints[(segmentIndex + 1) % waypoints.size]
            val nextDist = haversineDistance(nextFrom.lat, nextFrom.lon, nextTo.lat, nextTo.lon)
            if (nextDist > 0) {
                segmentProgress *= segmentDistM / nextDist
            }
        }
    }

    private fun moveToNextSegment() {
        segmentIndex = (segmentIndex + 1) % waypoints.size
    }

    companion object {
        private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        private fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val dLon = Math.toRadians(lon2 - lon1)
            val y = sin(dLon) * cos(Math.toRadians(lat2))
            val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                    sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
            return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }
    }
}
