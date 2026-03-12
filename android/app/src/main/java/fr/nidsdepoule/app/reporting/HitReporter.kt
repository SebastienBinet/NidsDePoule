package fr.nidsdepoule.app.reporting

import fr.nidsdepoule.app.sensor.LocationReading
import fr.nidsdepoule.app.ui.MapMarkerData
import fr.nidsdepoule.app.ui.MapMarkerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

/**
 * Orchestrates sending hit reports to the server.
 *
 * Supports two modes:
 * - Real-time: sends each hit immediately.
 * - Wi-Fi batch: buffers hits locally, sends when Wi-Fi is available.
 *
 * Delegates HTTP transport to [HttpClient] interface, making it testable.
 */
class HitReporter(
    private val httpClient: HttpClient,
    private val dataUsageTracker: DataUsageTracker,
    private val deviceId: String,
    private val appVersion: Int,
    var serverUrl: String,
    var onConnectivityChanged: ((Boolean) -> Unit)? = null,
) {
    enum class Mode { REALTIME, WIFI_BATCH }

    var mode: Mode = Mode.REALTIME

    // Local buffer for Wi-Fi batch mode
    private val buffer = mutableListOf<HitReportData>()
    val pendingCount: Int get() = buffer.size

    // Counters
    var hitsSent: Int = 0
        private set
    var hitsFailed: Int = 0
        private set
    var lastSendTimestampMs: Long = 0
        private set

    private val scope = CoroutineScope(Dispatchers.IO)

    // Heartbeat timer — sends current location every 10s
    private var heartbeatTimer: Timer? = null
    var lastKnownLocation: LocationReading? = null

    /**
     * Report a hit. In real-time mode, sends immediately.
     * In Wi-Fi batch mode, adds to the local buffer.
     */
    fun report(hitReport: HitReportData) {
        when (mode) {
            Mode.REALTIME -> {
                scope.launch { sendSingle(hitReport) }
            }
            Mode.WIFI_BATCH -> {
                synchronized(buffer) {
                    buffer.add(hitReport)
                    // Cap buffer at 10,000 hits
                    if (buffer.size > 10_000) {
                        buffer.removeAt(0)
                    }
                }
            }
        }
    }

    /**
     * Flush the batch buffer. Call when Wi-Fi becomes available.
     */
    fun flushBatch() {
        val toSend: List<HitReportData>
        synchronized(buffer) {
            toSend = buffer.toList()
            buffer.clear()
        }
        if (toSend.isEmpty()) return

        scope.launch {
            sendBatch(toSend)
        }
    }

    /** Ping the server health endpoint to determine initial connectivity. */
    fun checkConnectivity() {
        if (serverUrl.isBlank()) {
            onConnectivityChanged?.invoke(false)
            return
        }
        scope.launch {
            val result = httpClient.get("$serverUrl/health")
            onConnectivityChanged?.invoke(result.success)
        }
    }

    /**
     * Start sending periodic heartbeats with the device's current location.
     * Call once after the reporter is initialised (e.g. in ViewModel.start()).
     */
    fun startHeartbeat() {
        stopHeartbeat()
        heartbeatTimer = Timer("heartbeat", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    scope.launch { sendHeartbeat() }
                }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS)
        }
    }

    /** Stop the heartbeat timer. Call in ViewModel.stop(). */
    fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private suspend fun sendHeartbeat() {
        if (serverUrl.isBlank()) return
        val loc = lastKnownLocation
        val json = buildMap<String, Any?> {
            put("protocol_version", 1)
            put("device_id", deviceId)
            put("app_version", appVersion)
            put("heartbeat", buildMap<String, Any?> {
                put("timestamp_ms", System.currentTimeMillis())
                put("pending_hits", pendingCount)
                if (loc != null) {
                    put("location", mapOf(
                        "lat_microdeg" to loc.latMicrodeg,
                        "lon_microdeg" to loc.lonMicrodeg,
                        "accuracy_m" to loc.accuracyM,
                    ))
                }
            })
        }
        val jsonStr = JSONObject(json).toString()
        val url = "$serverUrl/api/v1/hits"
        val result = httpClient.postJson(url, jsonStr)
        if (result.success) {
            dataUsageTracker.record(result.bytesSent, result.bytesReceived, DataCategory.HEARTBEAT)
            onConnectivityChanged?.invoke(true)
        } else {
            onConnectivityChanged?.invoke(false)
        }
    }

    private suspend fun sendSingle(hitReport: HitReportData) {
        val jsonMap = hitReport.toJsonMap(deviceId, appVersion)
        val jsonStr = JSONObject(jsonMap).toString()
        val url = "$serverUrl/api/v1/hits"

        val result = httpClient.postJson(url, jsonStr)
        if (result.success) {
            hitsSent++
            lastSendTimestampMs = System.currentTimeMillis()
            dataUsageTracker.record(result.bytesSent, result.bytesReceived, DataCategory.HITS)
            onConnectivityChanged?.invoke(true)
        } else {
            hitsFailed++
            onConnectivityChanged?.invoke(false)
            // On failure in real-time mode, buffer the hit for retry
            synchronized(buffer) {
                buffer.add(hitReport)
            }
        }
    }

    private suspend fun sendBatch(hits: List<HitReportData>) {
        val batchJson = mapOf(
            "protocol_version" to 1,
            "device_id" to deviceId,
            "app_version" to appVersion,
            "batch" to mapOf(
                "hits" to hits.map { hit ->
                    mapOf(
                        "timestamp_ms" to hit.timestampMs,
                        "location" to mapOf(
                            "lat_microdeg" to hit.latMicrodeg,
                            "lon_microdeg" to hit.lonMicrodeg,
                            "accuracy_m" to hit.accuracyM,
                        ),
                        "speed_mps" to hit.speedMps,
                        "bearing_deg" to hit.bearingDeg,
                        "bearing_before_deg" to hit.bearingBeforeDeg,
                        "bearing_after_deg" to hit.bearingAfterDeg,
                        "pattern" to mapOf(
                            "severity" to hit.hit.severity,
                            "peak_vertical_mg" to hit.hit.peakVerticalMg,
                            "peak_lateral_mg" to hit.hit.peakLateralMg,
                            "duration_ms" to hit.hit.durationMs,
                            "waveform_vertical" to hit.hit.waveformVertical,
                            "waveform_lateral" to hit.hit.waveformLateral,
                            "baseline_mg" to hit.hit.baselineMg,
                            "peak_to_baseline_ratio" to hit.hit.peakToBaselineRatio,
                        ),
                    )
                }
            ),
        )
        val jsonStr = JSONObject(batchJson).toString()
        val url = "$serverUrl/api/v1/hits"

        val result = httpClient.postJson(url, jsonStr)
        if (result.success) {
            hitsSent += hits.size
            lastSendTimestampMs = System.currentTimeMillis()
            dataUsageTracker.record(result.bytesSent, result.bytesReceived, DataCategory.HITS)
            onConnectivityChanged?.invoke(true)
        } else {
            hitsFailed += hits.size
            onConnectivityChanged?.invoke(false)
            // Put back in buffer for retry
            synchronized(buffer) {
                buffer.addAll(0, hits)
            }
        }
    }

    // --- Server potholes fetching ---

    private var potholesTimer: Timer? = null
    var onPotholesFetched: ((List<MapMarkerData>) -> Unit)? = null

    /** Start periodically fetching pothole positions from the server. */
    fun startPotholesFetch() {
        stopPotholesFetch()
        // Fetch immediately, then every 30 seconds
        scope.launch { fetchPotholes() }
        potholesTimer = Timer("potholes", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    scope.launch { fetchPotholes() }
                }
            }, POTHOLES_INTERVAL_MS, POTHOLES_INTERVAL_MS)
        }
    }

    /** Stop the potholes fetch timer. */
    fun stopPotholesFetch() {
        potholesTimer?.cancel()
        potholesTimer = null
    }

    /** Fetch clustered potholes from the server and notify via callback. */
    private suspend fun fetchPotholes() {
        if (serverUrl.isBlank()) return
        val result = httpClient.get("$serverUrl/api/v1/potholes")
        if (!result.success) return
        dataUsageTracker.record(0, result.bytesReceived, DataCategory.POTHOLES)

        try {
            val json = JSONObject(result.body)
            val features = json.getJSONArray("features")
            val markers = mutableListOf<MapMarkerData>()
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)
                val props = feature.getJSONObject("properties")
                val lastSeenMs = props.optLong("last_seen_ms", 0)
                markers.add(MapMarkerData(
                    latMicrodeg = (lat * 1_000_000).toInt(),
                    lonMicrodeg = (lon * 1_000_000).toInt(),
                    type = MapMarkerType.SERVER,
                    timestampMs = lastSeenMs,
                ))
            }
            onPotholesFetched?.invoke(markers)
        } catch (_: Exception) {
            // Ignore parse errors
        }
    }

    companion object {
        /** Heartbeat interval in milliseconds (10 seconds). */
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        /** Potholes fetch interval in milliseconds (30 seconds). */
        const val POTHOLES_INTERVAL_MS = 30_000L
    }
}
