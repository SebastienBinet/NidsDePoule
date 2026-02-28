package fr.nidsdepoule.app.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.util.LruCache
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Async OSM tile loader with memory-only cache.
 *
 * Fetches 256x256 PNG tiles from OpenStreetMap and caches them in an LruCache.
 * All I/O happens on Dispatchers.IO — [getTile] returns immediately (null if not cached).
 * Compose recomposes when tiles arrive via the [revision] state counter.
 *
 * No SQLite, no disk cache — avoids the ANR that osmdroid caused.
 */
object OsmTileLoader {

    data class TileKey(val z: Int, val x: Int, val y: Int)

    private const val TILE_SIZE = 256
    private const val MAX_TILES = 30 // ~7MB max
    private const val MAX_CONCURRENT = 4

    private val cache = LruCache<TileKey, ImageBitmap>(MAX_TILES)
    private val inflight = mutableSetOf<TileKey>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)

    /** Incremented when any tile finishes loading. Compose observes this to recompose. */
    val revision = mutableIntStateOf(0)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Get a cached tile bitmap, or null if not yet loaded.
     * Triggers an async fetch if the tile isn't in cache or inflight.
     */
    fun getTile(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = TileKey(z, x, y)
        val cached = cache.get(key)
        if (cached != null) return cached

        synchronized(inflight) {
            if (key in inflight) return null
            inflight.add(key)
        }
        scope.launch { fetchTile(key) }
        return null
    }

    private suspend fun fetchTile(key: TileKey) {
        semaphore.acquire()
        try {
            val url = "https://tile.openstreetmap.org/${key.z}/${key.x}/${key.y}.png"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NidsDePoule/1.0 (pothole detection app)")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        cache.put(key, bitmap.asImageBitmap())
                        // Notify Compose to recompose
                        revision.intValue++
                    }
                }
            }
            response.close()
        } catch (_: Exception) {
            // Network failure — tile stays dark, no crash
        } finally {
            synchronized(inflight) { inflight.remove(key) }
            semaphore.release()
        }
    }

    // --- Tile math (Web Mercator / Slippy Map) ---

    fun lonToTileX(lonDeg: Double, z: Int): Int {
        return ((lonDeg + 180.0) / 360.0 * (1 shl z)).toInt()
    }

    fun latToTileY(latDeg: Double, z: Int): Int {
        val latRad = Math.toRadians(latDeg)
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl z)).toInt()
    }

    /** Top-left longitude of tile x at zoom z. */
    fun tileXToLon(x: Int, z: Int): Double {
        return x.toDouble() / (1 shl z) * 360.0 - 180.0
    }

    /** Top-left latitude of tile y at zoom z. */
    fun tileYToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y.toDouble() / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Convert latitude to Mercator Y (for linear interpolation within tiles). */
    fun latToMercatorY(latDeg: Double): Double {
        val latRad = Math.toRadians(latDeg)
        return ln(tan(PI / 4.0 + latRad / 2.0))
    }
}
