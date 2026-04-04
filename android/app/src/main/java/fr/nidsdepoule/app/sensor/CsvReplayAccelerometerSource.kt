package fr.nidsdepoule.app.sensor

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Replays accelerometer data from a CSV file recorded by AccelCsvRecorder.
 *
 * CSV format: timestamp_ms,x_mg,y_mg,z_mg,magnitude_mg,lat_microdeg,lon_microdeg,speed_mps,accuracy_m,bearing_deg
 *
 * Two replay modes:
 *   - Real-time: replays at original timing (for integration tests / demo)
 *   - Fast: replays as fast as possible (for unit tests / batch analysis)
 */
class CsvReplayAccelerometerSource(
    private val csvFile: File,
    private val realTime: Boolean = true,
) : AccelerometerSource {

    private val TAG = "CsvReplay"
    private var thread: Thread? = null
    @Volatile private var running = false

    override fun start(callback: AccelerometerCallback) {
        if (running) return
        running = true

        thread = Thread({
            try {
                replay(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Replay failed", e)
            } finally {
                running = false
            }
        }, "csv-replay").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun replay(callback: AccelerometerCallback) {
        val reader = BufferedReader(FileReader(csvFile))
        // Skip header
        val header = reader.readLine() ?: return

        var prevTimestamp = 0L
        var line = reader.readLine()

        while (line != null && running) {
            val parts = line.split(",")
            if (parts.size < 5) {
                line = reader.readLine()
                continue
            }

            try {
                val timestamp = parts[0].toLong()
                val xMg = parts[1].toInt()
                val yMg = parts[2].toInt()
                val zMg = parts[3].toInt()

                // Pace replay at original timing
                if (realTime && prevTimestamp > 0) {
                    val delayMs = timestamp - prevTimestamp
                    if (delayMs in 1..1000) {
                        Thread.sleep(delayMs)
                    }
                }
                prevTimestamp = timestamp

                callback.onReading(timestamp, xMg, yMg, zMg)
            } catch (_: NumberFormatException) {
                // Skip malformed lines
            } catch (_: InterruptedException) {
                break
            }

            line = reader.readLine()
        }

        reader.close()
        Log.i(TAG, "Replay complete: ${csvFile.name}")
    }

    /** Parsed CSV row with location data (for analysis tools). */
    data class CsvRow(
        val timestampMs: Long,
        val xMg: Int,
        val yMg: Int,
        val zMg: Int,
        val magnitudeMg: Int,
        val latMicrodeg: Int,
        val lonMicrodeg: Int,
        val speedMps: Float,
        val accuracyM: Int,
        val bearingDeg: Float,
    )

    companion object {
        /** Parse a CSV file into a list of rows (for batch analysis). */
        fun parseAll(csvFile: File): List<CsvRow> {
            val rows = mutableListOf<CsvRow>()
            val reader = BufferedReader(FileReader(csvFile))
            reader.readLine() // skip header

            var line = reader.readLine()
            while (line != null) {
                val p = line.split(",")
                if (p.size >= 10) {
                    try {
                        rows.add(CsvRow(
                            timestampMs = p[0].toLong(),
                            xMg = p[1].toInt(),
                            yMg = p[2].toInt(),
                            zMg = p[3].toInt(),
                            magnitudeMg = p[4].toInt(),
                            latMicrodeg = p[5].toInt(),
                            lonMicrodeg = p[6].toInt(),
                            speedMps = p[7].toFloat(),
                            accuracyM = p[8].toInt(),
                            bearingDeg = p[9].toFloat(),
                        ))
                    } catch (_: NumberFormatException) {}
                }
                line = reader.readLine()
            }
            reader.close()
            return rows
        }
    }
}
