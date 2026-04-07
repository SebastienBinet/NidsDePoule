package fr.nidsdepoule.capture.recording

import android.os.Build
import fr.nidsdepoule.capture.sensor.SensorInfo
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SessionMetadata(
    val sessionId: String,
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = Build.VERSION.RELEASE,
    val appVersion: String = "1.0",
    val startTimeEpochMs: Long = System.currentTimeMillis(),
    val startBootTimeNs: Long = android.os.SystemClock.elapsedRealtimeNanos(),
    val bootToEpochOffsetMs: Long = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime(),
    val sensors: Map<String, SensorInfo> = emptyMap(),
    var endTimeEpochMs: Long = 0L,
    var sampleCounts: Map<String, Long> = emptyMap(),
    var fileSizesBytes: Map<String, Long> = emptyMap(),
    // Post-capture annotation fields
    var routeOrigin: String = "",
    var routeDestination: String = "",
    var labelingMethod: String = "",      // "Boutons", "Boutons+Voix", "Voix"
    var labelingReliability: String = "", // "Boutons très fiable", etc.
) {
    private val isoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("session_id", sessionId)
        put("device_model", deviceModel)
        put("android_version", androidVersion)
        put("app_version", appVersion)
        put("start_time_iso", isoFormat.format(Date(startTimeEpochMs)))
        put("start_time_epoch_ms", startTimeEpochMs)
        put("start_boot_time_ns", startBootTimeNs)
        put("boot_to_epoch_offset_ms", bootToEpochOffsetMs)

        val sensorsObj = JSONObject()
        for ((key, info) in sensors) {
            sensorsObj.put(key, JSONObject().apply {
                put("name", info.name)
                put("vendor", info.vendor)
                put("resolution", info.resolution.toDouble())
                put("max_range", info.maxRange.toDouble())
                put("requested_delay_us", info.requestedDelayUs)
            })
        }
        put("sensors", sensorsObj)

        if (endTimeEpochMs > 0) {
            put("end_time_iso", isoFormat.format(Date(endTimeEpochMs)))
        }

        val countsObj = JSONObject()
        for ((key, count) in sampleCounts) {
            countsObj.put(key, count)
        }
        put("sample_counts", countsObj)

        val sizesObj = JSONObject()
        for ((key, size) in fileSizesBytes) {
            sizesObj.put(key, size)
        }
        put("file_sizes_bytes", sizesObj)

        // Route and labeling annotation
        if (routeOrigin.isNotBlank() || routeDestination.isNotBlank()) {
            put("route", JSONObject().apply {
                put("origin", routeOrigin)
                put("destination", routeDestination)
            })
        }
        if (labelingMethod.isNotBlank()) put("labeling_method", labelingMethod)
        if (labelingReliability.isNotBlank()) put("labeling_reliability", labelingReliability)
    }

    fun writeTo(file: File) {
        file.writeText(toJson().toString(2))
    }
}
