package fr.nidsdepoule.capture.recording

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import fr.nidsdepoule.capture.Version
import fr.nidsdepoule.capture.location.RouteNamer
import fr.nidsdepoule.capture.sensor.SensorInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Locale-safe float formatting for CSV output (always uses '.' as decimal separator). */
private fun csvFormat(fmt: String, vararg args: Any): String = String.format(Locale.US, fmt, *args)

/**
 * Orchestrates CSV writers for all sensor streams.
 *
 * All file I/O is dispatched to a dedicated HandlerThread so sensor callbacks
 * are never blocked by disk writes.
 */
class SessionRecorder(private val context: Context) {

    private val baseDir = File(context.getExternalFilesDir(null), "capture_sessions")

    private lateinit var sessionDir: File
    private lateinit var sessionId: String
    private lateinit var metadata: SessionMetadata

    private lateinit var accelWriter: CsvWriter
    private lateinit var gyroWriter: CsvWriter
    private lateinit var magWriter: CsvWriter
    private lateinit var gpsWriter: CsvWriter
    private lateinit var eventWriter: CsvWriter
    private val audioRecorder = AudioRecorder()

    private val ioThread = HandlerThread("csv-io").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    @Volatile var accelCount = 0L; private set
    @Volatile var gyroCount = 0L; private set
    @Volatile var magCount = 0L; private set
    @Volatile var gpsCount = 0L; private set
    @Volatile var eventCount = 0L; private set
    @Volatile var totalBytes = 0L; private set
    @Volatile var startTimeMs = 0L; private set

    val durationMs: Long get() = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0

    fun start(sensors: Map<String, SensorInfo>): String {
        // Reset all counters from previous session
        accelCount = 0; gyroCount = 0; magCount = 0; gpsCount = 0; eventCount = 0; totalBytes = 0

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "00000"
        val suffix = deviceId.takeLast(5)
        sessionId = "${timestamp}_$suffix"

        sessionDir = File(baseDir, "session_$sessionId").apply { mkdirs() }

        val ver = Version.CODE
        accelWriter = CsvWriter(File(sessionDir, "accel_$sessionId.csv"), "# $ver\ntimestamp_ns,x_ms2,y_ms2,z_ms2")
        gyroWriter = CsvWriter(File(sessionDir, "gyro_$sessionId.csv"), "# $ver\ntimestamp_ns,x_rads,y_rads,z_rads")
        magWriter = CsvWriter(File(sessionDir, "mag_$sessionId.csv"), "# $ver\ntimestamp_ns,x_ut,y_ut,z_ut")
        gpsWriter = CsvWriter(
            File(sessionDir, "gps_$sessionId.csv"),
            "# $ver\ntimestamp_ms,lat_deg,lon_deg,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg"
        )
        eventWriter = CsvWriter(
            File(sessionDir, "events_$sessionId.csv"),
            "# $ver\ntimestamp_ms,event_type,source"
        )

        // Start continuous audio recording
        audioRecorder.start(File(sessionDir, "audio_$sessionId.wav"))

        metadata = SessionMetadata(sessionId = sessionId, sensors = sensors)
        startTimeMs = System.currentTimeMillis()

        return sessionId
    }

    fun writeAccel(timestampNs: Long, x: Float, y: Float, z: Float) {
        ioHandler.post {
            accelWriter.writeLine(csvFormat("%d,%.6f,%.6f,%.6f", timestampNs, x, y, z))
            accelCount++
            totalBytes = computeTotalBytes()
        }
    }

    fun writeGyro(timestampNs: Long, x: Float, y: Float, z: Float) {
        ioHandler.post {
            gyroWriter.writeLine(csvFormat("%d,%.6f,%.6f,%.6f", timestampNs, x, y, z))
            gyroCount++
            totalBytes = computeTotalBytes()
        }
    }

    fun writeMag(timestampNs: Long, x: Float, y: Float, z: Float) {
        ioHandler.post {
            magWriter.writeLine(csvFormat("%d,%.6f,%.6f,%.6f", timestampNs, x, y, z))
            magCount++
            totalBytes = computeTotalBytes()
        }
    }

    fun writeGps(location: Location) {
        ioHandler.post {
            val line = csvFormat(
                "%d,%.8f,%.8f,%.2f,%.2f,%.1f,%.1f,%.1f,%.2f,%.1f",
                location.time,
                location.latitude, location.longitude,
                if (location.hasAltitude()) location.altitude else 0.0,
                if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
                if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else 0.0,
                if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else 0.0,
                if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else 0.0,
            )
            gpsWriter.writeLine(line)
            gpsCount++
            totalBytes = computeTotalBytes()
        }
    }

    /**
     * Record a user-generated event (BT button press, on-screen tap, etc.).
     * @param eventType e.g. "pothole", "crack", "rough", "other"
     * @param source e.g. "bt_button", "screen_button", "volume_key"
     */
    fun writeEvent(eventType: String, source: String) {
        ioHandler.post {
            eventWriter.writeLine("${System.currentTimeMillis()},$eventType,$source")
            eventCount++
            totalBytes = computeTotalBytes()
        }
    }

    fun stop() {
        // Stop audio recording first (runs on its own thread)
        audioRecorder.stop()

        // Post the close operations to the I/O thread so all pending writes finish first
        ioHandler.post {
            accelWriter.close()
            gyroWriter.close()
            magWriter.close()
            gpsWriter.close()
            eventWriter.close()

            metadata.endTimeEpochMs = System.currentTimeMillis()
            metadata.sampleCounts = mapOf(
                "accel" to accelCount,
                "gyro" to gyroCount,
                "mag" to magCount,
                "gps" to gpsCount,
                "events" to eventCount,
            )
            metadata.fileSizesBytes = mapOf(
                "accel" to accelWriter.bytesWritten,
                "gyro" to gyroWriter.bytesWritten,
                "mag" to magWriter.bytesWritten,
                "gps" to gpsWriter.bytesWritten,
                "events" to eventWriter.bytesWritten,
                "audio" to audioRecorder.bytesWritten,
            )
            metadata.writeTo(File(sessionDir, "meta_$sessionId.json"))
        }

        // Wait for all pending I/O to complete, then shut down the thread
        val latch = java.util.concurrent.CountDownLatch(1)
        ioHandler.post { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        ioThread.quitSafely()
    }

    fun listSessions(): List<SessionSummary> {
        if (!baseDir.exists()) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("session_") }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { dir ->
                try {
                    val sizeBytes = dir.listFiles()?.sumOf { it.length() } ?: 0
                    val metaFile = dir.listFiles()?.find { it.name.startsWith("meta_") && it.name.endsWith(".json") }
                    if (metaFile != null) {
                        val json = org.json.JSONObject(metaFile.readText())
                        val route = json.optJSONObject("route")
                        SessionSummary(
                            sessionId = json.optString("session_id", dir.name.removePrefix("session_")),
                            startTimeIso = json.optString("start_time_iso", ""),
                            sizeBytes = sizeBytes,
                            directory = dir,
                            routeOrigin = route?.optString("origin", "") ?: "",
                            routeDestination = route?.optString("destination", "") ?: "",
                            eventCount = json.optJSONObject("sample_counts")?.optLong("events", 0) ?: 0,
                            labelingMethod = json.optString("labeling_method", ""),
                        )
                    } else {
                        // No meta file — still show the session (e.g. older versions, interrupted recording)
                        SessionSummary(
                            sessionId = dir.name.removePrefix("session_"),
                            startTimeIso = "",
                            sizeBytes = sizeBytes,
                            directory = dir,
                        )
                    }
                } catch (e: Exception) {
                    // Parse error — show session with minimal info rather than hiding it
                    android.util.Log.w("SessionRecorder", "Failed to parse session ${dir.name}", e)
                    SessionSummary(
                        sessionId = dir.name.removePrefix("session_"),
                        startTimeIso = "",
                        sizeBytes = dir.listFiles()?.sumOf { it.length() } ?: 0,
                        directory = dir,
                    )
                }
            }
            ?: emptyList()
    }

    /**
     * Update the session metadata with post-capture annotations.
     * Rewrites meta JSON with route and labeling info.
     */
    fun annotateLastSession(
        routeOrigin: String,
        routeDestination: String,
        labelingMethod: String,
        labelingReliability: String,
        comment: String = "",
    ) {
        if (!::metadata.isInitialized) return
        metadata.routeOrigin = routeOrigin
        metadata.routeDestination = routeDestination
        metadata.labelingMethod = labelingMethod
        metadata.labelingReliability = labelingReliability
        metadata.comment = comment

        // Generate route abbreviation and add to metadata
        if (routeOrigin.isNotBlank() || routeDestination.isNotBlank()) {
            val abbrev = RouteNamer.buildAbbreviation(
                routeOrigin.ifBlank { "?" },
                routeDestination.ifBlank { "?" },
            )
            metadata.routeAbbreviation = abbrev

            // Rename session directory to include abbreviation
            if (::sessionDir.isInitialized && ::sessionId.isInitialized) {
                val newName = "session_${sessionId}_$abbrev"
                val newDir = File(sessionDir.parentFile, newName)
                if (sessionDir.renameTo(newDir)) {
                    sessionDir = newDir
                }
            }
        }

        // Rewrite the meta file
        if (::sessionDir.isInitialized && ::sessionId.isInitialized) {
            metadata.writeTo(File(sessionDir, "meta_$sessionId.json"))
        }
    }

    /** Returns event counts by type for the current/last session. */
    fun getEventCountsByType(): Map<String, Long> {
        if (!::sessionDir.isInitialized || !::sessionId.isInitialized) return emptyMap()
        val eventsFile = File(sessionDir, "events_$sessionId.csv")
        if (!eventsFile.exists()) return emptyMap()
        val counts = mutableMapOf<String, Long>()
        eventsFile.readLines().drop(1).forEach { line ->
            val parts = line.split(",")
            if (parts.size >= 2) {
                val type = parts[1].trim()
                counts[type] = (counts[type] ?: 0) + 1
            }
        }
        return counts
    }

    /** Find a session directory by ID, handling renamed dirs (with route abbreviation suffix). */
    fun findSessionDir(sessionId: String): File? {
        if (!baseDir.exists()) return null
        return baseDir.listFiles()?.find {
            it.isDirectory && it.name.startsWith("session_$sessionId")
        }
    }

    fun deleteSession(sessionId: String): Boolean {
        val dir = findSessionDir(sessionId) ?: return false
        return dir.deleteRecursively()
    }

    private fun computeTotalBytes(): Long =
        accelWriter.bytesWritten + gyroWriter.bytesWritten + magWriter.bytesWritten + gpsWriter.bytesWritten + eventWriter.bytesWritten + audioRecorder.bytesWritten
}

data class SessionSummary(
    val sessionId: String,
    val startTimeIso: String,
    val sizeBytes: Long,
    val directory: File,
    val routeOrigin: String = "",
    val routeDestination: String = "",
    val eventCount: Long = 0,
    val labelingMethod: String = "",
)
