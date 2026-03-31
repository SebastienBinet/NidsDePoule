package fr.nidsdepoule.app.detection

import android.content.Context
import android.util.Log
import fr.nidsdepoule.app.sensor.LocationReading
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records accelerometer + GPS data to a CSV file for offline analysis
 * and test vector generation. Activated via dev mode toggle.
 *
 * CSV columns:
 *   timestamp_ms, x_mg, y_mg, z_mg, magnitude_mg, lat_microdeg, lon_microdeg, speed_mps, accuracy_m, bearing_deg
 *
 * Files are saved to the app's external files directory under recordings/.
 */
class AccelCsvRecorder(private val context: Context) {

    private val TAG = "AccelCsvRecorder"

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var lastLocation: LocationReading? = null
    var isRecording: Boolean = false
        private set

    /** Start recording to a new timestamped CSV file. */
    fun start() {
        if (isRecording) return

        val dir = File(context.getExternalFilesDir(null), "recordings")
        dir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "accel_$timestamp.csv")

        try {
            val bw = BufferedWriter(FileWriter(file))
            bw.write("timestamp_ms,x_mg,y_mg,z_mg,magnitude_mg,lat_microdeg,lon_microdeg,speed_mps,accuracy_m,bearing_deg")
            bw.newLine()
            writer = bw
            currentFile = file
            isRecording = true
            Log.i(TAG, "Recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    /** Stop recording and close the file. Returns the file path, or null if not recording. */
    fun stop(): String? {
        if (!isRecording) return null
        isRecording = false
        val path = currentFile?.absolutePath
        try {
            writer?.flush()
            writer?.close()
            Log.i(TAG, "Recording stopped: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close recording", e)
        }
        writer = null
        currentFile = null
        return path
    }

    /** Update the latest GPS reading (called from location callback). */
    fun updateLocation(location: LocationReading) {
        lastLocation = location
    }

    /** Record one accelerometer sample with the latest GPS position. Call at sensor rate (~50Hz). */
    fun addSample(timestampMs: Long, xMg: Int, yMg: Int, zMg: Int, magnitudeMg: Int) {
        val w = writer ?: return
        val loc = lastLocation
        try {
            w.write(buildString {
                append(timestampMs).append(',')
                append(xMg).append(',')
                append(yMg).append(',')
                append(zMg).append(',')
                append(magnitudeMg).append(',')
                if (loc != null) {
                    append(loc.latMicrodeg).append(',')
                    append(loc.lonMicrodeg).append(',')
                    append(String.format(Locale.US, "%.2f", loc.speedMps)).append(',')
                    append(loc.accuracyM).append(',')
                    append(String.format(Locale.US, "%.1f", loc.bearingDeg))
                } else {
                    append("0,0,0.00,0,0.0")
                }
            })
            w.newLine()
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
        }
    }

    /** List existing recording files. */
    fun listRecordings(): List<File> {
        val dir = File(context.getExternalFilesDir(null), "recordings")
        return dir.listFiles()?.filter { it.extension == "csv" }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
