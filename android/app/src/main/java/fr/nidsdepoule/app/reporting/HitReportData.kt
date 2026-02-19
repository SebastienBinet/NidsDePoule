package fr.nidsdepoule.app.reporting

import fr.nidsdepoule.app.detection.HitEvent
import fr.nidsdepoule.app.sensor.LocationReading

/**
 * A complete hit report ready to be sent to the server.
 * Combines the detection event with location context.
 */
data class HitReportData(
    val timestampMs: Long,
    val latMicrodeg: Int,
    val lonMicrodeg: Int,
    val accuracyM: Int,
    val speedMps: Float,
    val bearingDeg: Float,
    val bearingBeforeDeg: Float,
    val bearingAfterDeg: Float,
    val hit: HitEvent,
) {
    /**
     * Serialize to the JSON format expected by the server (Phase 1).
     * Returns a map that can be serialized to JSON.
     */
    fun toJsonMap(deviceId: String, appVersion: Int, protocolVersion: Int = 1): Map<String, Any?> {
        return mapOf(
            "protocol_version" to protocolVersion,
            "device_id" to deviceId,
            "app_version" to appVersion,
            "hit" to mapOf(
                "timestamp_ms" to timestampMs,
                "location" to mapOf(
                    "lat_microdeg" to latMicrodeg,
                    "lon_microdeg" to lonMicrodeg,
                    "accuracy_m" to accuracyM,
                ),
                "speed_mps" to speedMps,
                "bearing_deg" to bearingDeg,
                "bearing_before_deg" to bearingBeforeDeg,
                "bearing_after_deg" to bearingAfterDeg,
                "pattern" to mapOf(
                    "severity" to hit.severity,
                    "peak_vertical_mg" to hit.peakVerticalMg,
                    "peak_lateral_mg" to hit.peakLateralMg,
                    "duration_ms" to hit.durationMs,
                    "waveform_vertical" to hit.waveformVertical,
                    "waveform_lateral" to hit.waveformLateral,
                    "baseline_mg" to hit.baselineMg,
                    "peak_to_baseline_ratio" to hit.peakToBaselineRatio,
                ),
            ),
        )
    }

    companion object {
        fun create(hit: HitEvent, location: LocationReading, bearingBefore: Float, bearingAfter: Float): HitReportData {
            return HitReportData(
                timestampMs = hit.timestampMs,
                latMicrodeg = location.latMicrodeg,
                lonMicrodeg = location.lonMicrodeg,
                accuracyM = location.accuracyM,
                speedMps = location.speedMps,
                bearingDeg = location.bearingDeg,
                bearingBeforeDeg = bearingBefore,
                bearingAfterDeg = bearingAfter,
                hit = hit,
            )
        }
    }
}
