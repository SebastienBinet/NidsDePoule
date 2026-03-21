package fr.nidsdepoule.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
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
 * Async OSM tile loader with offline-first lookup.
 *
 * Lookup order: memory LruCache → offline MBTiles bundle → network fetch.
 * Compose recomposes when tiles arrive via the [revision] state counter.
 */
object OsmTileLoader {

    data class TileKey(val z: Int, val x: Int, val y: Int)

    private const val TILE_SIZE = 256
    private const val MAX_TILES = 200 // ~51MB max (zoom-17 viewport needs ~36 tiles)
    private const val MAX_CONCURRENT = 4

    /** Callback to record tile download bytes. Set from MainViewModel. */
    var onTileBytes: ((bytesReceived: Int) -> Unit)? = null

    private val cache = LruCache<TileKey, ImageBitmap>(MAX_TILES)
    private val inflight = mutableSetOf<TileKey>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)

    /** Incremented when any tile finishes loading. Compose observes this to recompose. */
    val revision = mutableIntStateOf(0)

    private var offlineStore: OfflineTileStore? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Initialize the offline tile store. Call from MainActivity.onCreate().
     */
    fun init(context: Context) {
        val store = OfflineTileStore(context)
        offlineStore = store
        store.onReady = {
            // Trigger recompose so tiles that failed during init get retried
            revision.intValue++
        }
        store.init(scope)
    }

    /**
     * Get a cached tile bitmap, or null if not yet loaded.
     *
     * Lookup order: memory cache → offline MBTiles → async network fetch.
     */
    fun getTile(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = TileKey(z, x, y)
        val cached = cache.get(key)
        if (cached != null) return cached

        // Try offline store (synchronous, fast SQLite indexed read)
        offlineStore?.let { store ->
            if (store.isReady) {
                // Direct lookup at requested zoom
                store.getTileBytes(z, x, y)?.let { bytes ->
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val img = bitmap.asImageBitmap()
                        cache.put(key, img)
                        return img
                    }
                }
                // Overzoom: if z > maxZoom, crop the parent tile at maxZoom
                if (z > store.maxZoom) {
                    val overzoomedImg = getOverzoomedTile(store, z, x, y)
                    if (overzoomedImg != null) {
                        cache.put(key, overzoomedImg)
                        return overzoomedImg
                    }
                }
            }
        }

        // Fall back to network fetch
        synchronized(inflight) {
            if (key in inflight) return null
            inflight.add(key)
        }
        scope.launch { fetchTile(key) }
        return null
    }

    /**
     * Overzoom: get a parent tile at maxZoom and crop/scale the sub-region
     * that corresponds to (z, x, y).
     */
    private fun getOverzoomedTile(store: OfflineTileStore, z: Int, x: Int, y: Int): ImageBitmap? {
        val dz = z - store.maxZoom
        val parentX = x shr dz
        val parentY = y shr dz
        val parentBytes = store.getTileBytes(store.maxZoom, parentX, parentY) ?: return null
        val parentBitmap = BitmapFactory.decodeByteArray(parentBytes, 0, parentBytes.size) ?: return null

        // Which sub-tile within the parent: 0..(2^dz - 1) in each axis
        val divisions = 1 shl dz
        val subX = x - (parentX shl dz)
        val subY = y - (parentY shl dz)
        val subSize = TILE_SIZE / divisions
        val srcLeft = subX * subSize
        val srcTop = subY * subSize
        val srcRect = Rect(srcLeft, srcTop, srcLeft + subSize, srcTop + subSize)
        val dstRect = Rect(0, 0, TILE_SIZE, TILE_SIZE)

        val result = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(parentBitmap, srcRect, dstRect, null)
        parentBitmap.recycle()
        return result.asImageBitmap()
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
                    onTileBytes?.invoke(bytes.size)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        cache.put(TileKey(key.z, key.x, key.y), bitmap.asImageBitmap())
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
