package fr.nidsdepoule.app.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Read-only MBTiles tile store backed by a SQLite database.
 *
 * On first launch, copies `montreal_tiles.mbtiles` from assets to
 * [Context.getFilesDir] (Android cannot open SQLite directly from assets).
 * Subsequent launches skip the copy via a version marker file.
 *
 * Thread-safe: the database is opened read-only and SQLite allows
 * concurrent reads without locking.
 */
class OfflineTileStore(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private val ready = CompletableDeferred<Boolean>()

    /** Version string — bump when the mbtiles asset is regenerated. */
    private val assetVersion = "2"
    private val assetName = "montreal_tiles.mbtiles"

    /** Maximum zoom level available in the mbtiles file. */
    var maxZoom: Int = 15
        private set

    /** Callback invoked on the IO dispatcher when the store becomes ready. */
    var onReady: (() -> Unit)? = null

    /** Start the async copy-from-assets if needed, then open the database. */
    fun init(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val dbFile = File(context.filesDir, assetName)
                val versionFile = File(context.filesDir, "$assetName.version")

                // Check if we need to copy
                val currentVersion = if (versionFile.exists()) versionFile.readText() else ""
                if (!dbFile.exists() || currentVersion != assetVersion) {
                    Log.d("OfflineTileStore", "Copying $assetName from assets...")
                    context.assets.open(assetName).use { input ->
                        dbFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    versionFile.writeText(assetVersion)
                    Log.d("OfflineTileStore", "Copy complete: ${dbFile.length() / 1024} KB")
                }

                val database = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                db = database

                // Read maxzoom from metadata
                database.rawQuery(
                    "SELECT value FROM metadata WHERE name='maxzoom'",
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        maxZoom = cursor.getString(0).toIntOrNull() ?: 15
                    }
                }

                ready.complete(true)
                Log.d("OfflineTileStore", "Database opened, maxZoom=$maxZoom")
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e("OfflineTileStore", "Failed to initialize", e)
                ready.complete(false)
            }
        }
    }

    /** Whether the offline store is ready for queries. Non-blocking. */
    val isReady: Boolean get() = ready.isCompleted && (db != null)

    /**
     * Look up a tile by zoom/x/y (OSM convention).
     * Returns the raw PNG bytes or null if not found.
     *
     * This is a fast synchronous SQLite indexed read (~0.1 ms).
     */
    fun getTileBytes(z: Int, x: Int, y: Int): ByteArray? {
        val database = db ?: return null
        // MBTiles uses TMS y-coordinate convention
        val tmsY = (1 shl z) - 1 - y
        val cursor = database.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsY.toString()),
        )
        return cursor.use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }
    }
}
