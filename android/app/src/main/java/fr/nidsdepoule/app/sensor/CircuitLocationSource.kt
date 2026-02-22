package fr.nidsdepoule.app.sensor

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import kotlin.math.*

/**
 * LocationSource that replays a circuit JSON file from assets.
 *
 * Emits LocationReadings at 1 Hz by interpolating between waypoints.
 * Loops continuously. At "stop" waypoints (suggested_speed_kmh == 0),
 * pauses for 2 seconds before continuing.
 *
 * Also injects mock locations into the Android system via
 * LocationManager.setTestProviderLocation(), so that Google Maps,
 * Waze, and other apps see the simulated position.
 *
 * Prerequisite: the user must enable Developer Options on the device
 * and select this app as the "mock location app" in Developer Options.
 */
class CircuitLocationSource(private val context: Context) : LocationSource {

    private data class Waypoint(
        val lat: Double,
        val lon: Double,
        val bearingDeg: Float,
        val suggestedSpeedKmh: Float,
        val isStop: Boolean,
    )

    companion object {
        private const val TAG = "CircuitSim"
        private const val PROVIDER = LocationManager.GPS_PROVIDER

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

    private val handler = Handler(Looper.getMainLooper())
    private var callback: LocationCallback? = null
    private var waypoints: List<Waypoint> = emptyList()
    private var running = false
    private var mockProviderAdded = false

    // Current interpolation state
    private var segmentIndex = 0
    private var segmentProgress = 0.0
    private var stopTicksRemaining = 0

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

        setupMockProvider()
        handler.post(tickRunnable)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        callback = null
        teardownMockProvider()
    }

    /**
     * Register as a mock GPS provider so other apps see our position.
     * Requires the user to select this app as "mock location app" in
     * Developer Options.
     */
    private fun setupMockProvider() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try { lm.removeTestProvider(PROVIDER) } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lm.addTestProvider(
                    PROVIDER,
                    false,  // requiresNetwork
                    false,  // requiresSatellite
                    false,  // requiresCell
                    false,  // hasMonetaryCost
                    true,   // supportsAltitude
                    true,   // supportsSpeed
                    true,   // supportsBearing
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE,
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE,
                )
            }
            lm.setTestProviderEnabled(PROVIDER, true)
            mockProviderAdded = true
            Log.i(TAG, "Mock GPS provider registered — other apps will see simulated position")
        } catch (e: SecurityException) {
            mockProviderAdded = false
            Log.w(TAG, "Cannot set mock provider — enable Developer Options and select this app as mock location app", e)
        } catch (e: Exception) {
            mockProviderAdded = false
            Log.w(TAG, "Mock provider setup failed", e)
        }
    }

    private fun teardownMockProvider() {
        if (!mockProviderAdded) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.setTestProviderEnabled(PROVIDER, false)
            lm.removeTestProvider(PROVIDER)
            Log.i(TAG, "Mock GPS provider removed")
        } catch (e: Exception) {
            Log.w(TAG, "Mock provider teardown failed", e)
        }
        mockProviderAdded = false
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

        val lat = from.lat + (to.lat - from.lat) * t
        val lon = from.lon + (to.lon - from.lon) * t
        val bearing = computeBearing(from.lat, from.lon, to.lat, to.lon)
        val speedMps = from.suggestedSpeedKmh / 3.6f

        // Feed our own app
        cb.onLocation(LocationReading(
            timestampMs = System.currentTimeMillis(),
            latMicrodeg = (lat * 1_000_000).toInt(),
            lonMicrodeg = (lon * 1_000_000).toInt(),
            accuracyM = 3,
            speedMps = speedMps,
            bearingDeg = bearing,
        ))

        // Inject into Android system so Google Maps / Waze see it too
        if (mockProviderAdded) {
            try {
                val loc = Location(PROVIDER).apply {
                    latitude = lat
                    longitude = lon
                    altitude = 60.0  // approximate Montreal elevation
                    accuracy = 3f
                    speed = speedMps
                    this.bearing = bearing
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.setTestProviderLocation(PROVIDER, loc)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject mock location", e)
            }
        }
    }

    private fun advancePosition() {
        if (stopTicksRemaining > 0) {
            stopTicksRemaining--
            return
        }

        val from = waypoints[segmentIndex]
        val to = waypoints[(segmentIndex + 1) % waypoints.size]
        val segmentDistM = haversineDistance(from.lat, from.lon, to.lat, to.lon)
        val speedMps = from.suggestedSpeedKmh / 3.6f

        if (segmentDistM <= 0 || speedMps <= 0) {
            moveToNextSegment()
            return
        }

        val progressPerTick = speedMps / segmentDistM
        segmentProgress += progressPerTick

        while (segmentProgress >= 1.0) {
            segmentProgress -= 1.0
            moveToNextSegment()

            val current = waypoints[segmentIndex]
            if (current.isStop) {
                stopTicksRemaining = 2
                segmentProgress = 0.0
                break
            }

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
}
