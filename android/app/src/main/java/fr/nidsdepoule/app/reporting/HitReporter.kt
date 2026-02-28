package fr.nidsdepoule.app.reporting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    private suspend fun sendSingle(hitReport: HitReportData) {
        val jsonMap = hitReport.toJsonMap(deviceId, appVersion)
        val jsonStr = JSONObject(jsonMap).toString()
        val url = "$serverUrl/api/v1/hits"

        val result = httpClient.postJson(url, jsonStr)
        if (result.success) {
            hitsSent++
            lastSendTimestampMs = System.currentTimeMillis()
            dataUsageTracker.record(result.bytesSent, result.bytesReceived)
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
            dataUsageTracker.record(result.bytesSent, result.bytesReceived)
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
}
