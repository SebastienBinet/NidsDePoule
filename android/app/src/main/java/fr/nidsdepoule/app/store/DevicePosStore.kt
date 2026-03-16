package fr.nidsdepoule.app.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
 */
class DevicePosStore(private val context: Context) {

    private val assetName = "remplissage_niddepoule_2025.gpkg"
    private val assetVersion = "1"

    /**
     * Flat IntArray of [latMicrodeg, lonMicrodeg, latMicrodeg, lonMicrodeg, ...].
     * Size = pointCount * 2. Access: lat = positions[i*2], lon = positions[i*2+1].
     */
    private val _positions = MutableStateFlow(IntArray(0))
    val positions: StateFlow<IntArray> = _positions

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

                val result = loadPositions(db)
                db.close()

                _positions.value = result
                Log.d(TAG, "Loaded ${result.size / 2} device positions")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load device positions", e)
            }
        }
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

    companion object {
        private const val TAG = "DevicePosStore"
    }
}
