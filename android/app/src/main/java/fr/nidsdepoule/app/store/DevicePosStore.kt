package fr.nidsdepoule.app.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Loads pothole repair positions from the bundled GeoPackage asset.
 *
 * The gpkg is a SQLite database containing ~74K points in EPSG:2950
 * (NAD83/MTM zone 8). We convert them to WGS84 lat/lon on load and
 * store as a compact IntArray (latMicrodeg, lonMicrodeg pairs).
 *
 * Positions are sorted by latitude for binary-search range queries.
 * A low-resolution overlay bitmap is pre-rendered for the Montreal
 * island view (shown when the map is pressed).
 */
class DevicePosStore(private val context: Context) {

    private val assetName = "remplissage_niddepoule_2025.gpkg"
    private val assetVersion = "1"

    /**
     * Flat IntArray sorted by latitude (microdeg).
     * Layout: [lat0, lon0, lat1, lon1, ...] where lat0 <= lat1 <= ...
     */
    private val _positions = MutableStateFlow(IntArray(0))
    val positions: StateFlow<IntArray> = _positions

    /** Pre-rendered overlay bitmap covering Montreal island. */
    private val _overlay = MutableStateFlow<Bitmap?>(null)
    val overlay: StateFlow<Bitmap?> = _overlay

    fun init(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val dbFile = File(context.filesDir, assetName)
                val versionFile = File(context.filesDir, "$assetName.version")

                val currentVersion = if (versionFile.exists()) versionFile.readText() else ""
                if (!dbFile.exists() || currentVersion != assetVersion) {
                    Log.d(TAG, "Copying $assetName from assets...")
                    context.assets.open(assetName).use { input ->
                        dbFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    versionFile.writeText(assetVersion)
                    Log.d(TAG, "Copy complete: ${dbFile.length() / 1024} KB")
                }

                val db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )

                val raw = loadPositions(db)
                db.close()

                // Sort pairs by latitude for binary-search range queries
                val sorted = sortByLatitude(raw)
                _positions.value = sorted
                Log.d(TAG, "Loaded ${sorted.size / 2} device positions (sorted)")

                // Pre-render Montreal overlay
                val bmp = renderOverlay(sorted)
                _overlay.value = bmp
                Log.d(TAG, "Montreal overlay rendered: ${bmp.width}x${bmp.height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load device positions", e)
            }
        }
    }

    /**
     * Binary-search the sorted positions array to find indices within [minLat, maxLat].
     * Returns the start index (inclusive, pair-aligned) and end index (exclusive, pair-aligned).
     */
    fun latRange(minLatMicro: Int, maxLatMicro: Int): Pair<Int, Int> {
        val arr = _positions.value
        if (arr.isEmpty()) return Pair(0, 0)
        val count = arr.size / 2
        // Find first pair with lat >= minLatMicro
        var lo = 0; var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (arr[mid * 2] < minLatMicro) lo = mid + 1 else hi = mid
        }
        val start = lo
        // Find first pair with lat > maxLatMicro
        lo = start; hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (arr[mid * 2] <= maxLatMicro) lo = mid + 1 else hi = mid
        }
        return Pair(start * 2, lo * 2)
    }

    private fun loadPositions(db: SQLiteDatabase): IntArray {
        // GeoPackage stores geometry as GeoPackage Binary (gpb) in the geom column.
        // For POINT geometry, the WKB payload inside gpb contains:
        //   gpb header (variable length) + WKB: byte_order(1) + type(4) + x(8) + y(8)
        //
        // We extract X (easting) and Y (northing) from the WKB portion,
        // then convert from EPSG:2950 to WGS84.

        val cursor = db.rawQuery(
            "SELECT geom FROM remplissage_niddepoule_2025",
            null,
        )

        val list = IntArray(cursor.count * 2)
        var idx = 0

        cursor.use {
            while (it.moveToNext()) {
                val blob = it.getBlob(0)
                val (easting, northing) = parseGpkgPoint(blob)
                val (lat, lon) = MtmProjection.toLatLon(easting, northing)
                list[idx++] = (lat * 1_000_000).toInt()
                list[idx++] = (lon * 1_000_000).toInt()
            }
        }

        return if (idx == list.size) list else list.copyOf(idx)
    }

    /**
     * Parse a GeoPackage Binary (GPB) POINT geometry to extract X, Y coordinates.
     *
     * GPB format:
     *   - magic: 2 bytes ("GP" = 0x47, 0x50)
     *   - version: 1 byte
     *   - flags: 1 byte (bits 1-3 = envelope type, bit 0 = byte order)
     *   - srs_id: 4 bytes
     *   - envelope: variable (0, 32, 48, 64 bytes depending on envelope type)
     *   - WKB geometry follows
     *
     * WKB Point:
     *   - byte order: 1 byte (0=big-endian, 1=little-endian)
     *   - type: 4 bytes (1=Point)
     *   - x: 8 bytes (double)
     *   - y: 8 bytes (double)
     */
    private fun parseGpkgPoint(blob: ByteArray): Pair<Double, Double> {
        // Read flags byte to determine envelope size and byte order
        val flags = blob[3].toInt() and 0xFF
        val gpbByteOrder = flags and 0x01  // 0=big-endian, 1=little-endian
        val envelopeType = (flags shr 1) and 0x07

        val envelopeSize = when (envelopeType) {
            0 -> 0
            1 -> 32   // minx, maxx, miny, maxy
            2 -> 48   // + minz, maxz
            3 -> 48   // + minm, maxm
            4 -> 64   // + minz, maxz, minm, maxm
            else -> 0
        }

        // GPB header = 4 (magic+version+flags) + 4 (srs_id) + envelope
        val wkbOffset = 8 + envelopeSize

        // WKB: byte_order(1) + type(4) + x(8) + y(8)
        val wkbOrder = blob[wkbOffset].toInt() and 0xFF  // 0=BE, 1=LE
        val xOffset = wkbOffset + 5
        val yOffset = wkbOffset + 13

        val x = readDouble(blob, xOffset, wkbOrder == 1)
        val y = readDouble(blob, yOffset, wkbOrder == 1)

        return Pair(x, y)
    }

    private fun readDouble(bytes: ByteArray, offset: Int, littleEndian: Boolean): Double {
        var bits = 0L
        if (littleEndian) {
            for (i in 7 downTo 0) {
                bits = (bits shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
        } else {
            for (i in 0..7) {
                bits = (bits shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
        }
        return Double.fromBits(bits)
    }

    /** Sort (lat, lon) pairs by latitude ascending. */
    private fun sortByLatitude(arr: IntArray): IntArray {
        val count = arr.size / 2
        // Build index array, sort by lat, then rebuild IntArray
        val indices = IntArray(count) { it }
        // Simple insertion sort would be slow for 74K; use array of pairs approach
        data class LatLon(val lat: Int, val lon: Int)
        val pairs = Array(count) { LatLon(arr[it * 2], arr[it * 2 + 1]) }
        pairs.sortBy { it.lat }
        val sorted = IntArray(arr.size)
        for (i in 0 until count) {
            sorted[i * 2] = pairs[i].lat
            sorted[i * 2 + 1] = pairs[i].lon
        }
        return sorted
    }

    /**
     * Pre-render a low-resolution overlay bitmap covering Montreal island.
     * Each cell that contains at least one point gets a grey pixel.
     * This avoids drawing 74K individual crosses in the Montreal overview.
     */
    private fun renderOverlay(positions: IntArray): Bitmap {
        val w = OVERLAY_W
        val h = OVERLAY_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val latRange = OVERLAY_MAX_LAT - OVERLAY_MIN_LAT
        val lonRange = OVERLAY_MAX_LON - OVERLAY_MIN_LON

        // Count points per cell for density-based alpha
        val counts = IntArray(w * h)
        val count = positions.size / 2
        for (i in 0 until count) {
            val lat = positions[i * 2] / 1_000_000.0
            val lon = positions[i * 2 + 1] / 1_000_000.0
            val px = ((lon - OVERLAY_MIN_LON) / lonRange * w).toInt()
            val py = ((OVERLAY_MAX_LAT - lat) / latRange * h).toInt()
            if (px in 0 until w && py in 0 until h) {
                counts[py * w + px]++
            }
        }

        // Find max count for normalization
        var maxCount = 1
        for (c in counts) if (c > maxCount) maxCount = c

        // Render: grey pixels with alpha based on density
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = counts[y * w + x]
                if (c > 0) {
                    // Alpha: min 80, max 220, scaled by log density
                    val frac = Math.log(c.toDouble() + 1) / Math.log(maxCount.toDouble() + 1)
                    val alpha = (80 + 140 * frac).toInt().coerceIn(80, 220)
                    // Grey: 0x9E9E9E with computed alpha
                    bmp.setPixel(x, y, (alpha shl 24) or 0x9E9E9E)
                }
            }
        }

        return bmp
    }

    companion object {
        private const val TAG = "DevicePosStore"

        // Montreal island bounding box (must match RouteMapWidget)
        const val OVERLAY_MIN_LAT = 45.40
        const val OVERLAY_MAX_LAT = 45.72
        const val OVERLAY_MIN_LON = -73.98
        const val OVERLAY_MAX_LON = -73.47

        // Overlay resolution (pixels)
        const val OVERLAY_W = 256
        const val OVERLAY_H = 160
    }
}
