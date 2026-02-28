package fr.nidsdepoule.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import fr.nidsdepoule.app.sensor.LocationReading
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Type of map marker event.
 */
enum class MapMarkerType { HIT, ALMOST }

/**
 * A map marker representing a Hit or Almost event.
 */
data class MapMarkerData(
    val latMicrodeg: Int,
    val lonMicrodeg: Int,
    val type: MapMarkerType,
    val timestampMs: Long,
)

/**
 * Map widget showing the last 30 seconds of driving route with Hit/Almost markers.
 *
 * Uses osmdroid with OpenStreetMap tiles (no API key required).
 * - Blue polyline for the route
 * - Red flashing marker for Hit events
 * - Amber flashing marker for Almost events
 */
@Composable
fun RouteMapWidget(
    locationHistory: List<LocationReading>,
    markers: List<MapMarkerData>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The default MapView(ctx) constructor creates a MapTileProviderBasic
    // internally, which opens an SQLite tile-cache database — heavy I/O that
    // blocks the main thread and causes ANR.
    //
    // Fix: pre-build the tile provider on Dispatchers.IO, then pass it to
    // MapView(ctx, tileProvider) so the constructor does no I/O.
    var tileProvider by remember { mutableStateOf<MapTileProviderBasic?>(null) }

    LaunchedEffect(Unit) {
        val provider = withContext(Dispatchers.IO) {
            val config = Configuration.getInstance()
            config.userAgentValue = context.packageName
            config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            MapTileProviderBasic(context)
        }
        tileProvider = provider
    }

    // Flash animation for markers
    val infiniteTransition = rememberInfiniteTransition(label = "markerFlash")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flashAlpha",
    )

    val shape = RoundedCornerShape(8.dp)

    val provider = tileProvider
    if (provider != null && !fr.nidsdepoule.app.DebugFlags.DISABLE_MAP) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(shape),
            factory = { ctx ->
                MapView(ctx, provider).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)
                    setScrollableAreaLimitLatitude(90.0, -90.0, 0)
                }
            },
            update = { mapView ->
                mapView.overlays.clear()

                // Draw route polyline
                if (locationHistory.size >= 2) {
                    val polyline = Polyline().apply {
                        outlinePaint.color = android.graphics.Color.argb(200, 33, 150, 243)
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.isAntiAlias = true
                    }
                    val points = locationHistory.map { reading ->
                        GeoPoint(
                            reading.latMicrodeg / 1_000_000.0,
                            reading.lonMicrodeg / 1_000_000.0,
                        )
                    }
                    polyline.setPoints(points)
                    mapView.overlays.add(polyline)

                    val last = points.last()
                    mapView.controller.setCenter(last)
                }

                // Draw markers
                for (markerData in markers) {
                    val point = GeoPoint(
                        markerData.latMicrodeg / 1_000_000.0,
                        markerData.lonMicrodeg / 1_000_000.0,
                    )

                    val isRecent = System.currentTimeMillis() - markerData.timestampMs < 10_000
                    val alpha = if (isRecent) (flashAlpha * 255).toInt() else 200

                    val color = when (markerData.type) {
                        MapMarkerType.HIT -> android.graphics.Color.argb(alpha, 211, 47, 47)
                        MapMarkerType.ALMOST -> android.graphics.Color.argb(alpha, 255, 143, 0)
                    }

                    val drawable = GradientDrawable().apply {
                        this.shape = GradientDrawable.OVAL
                        setSize(32, 32)
                        setColor(color)
                        setStroke(3, android.graphics.Color.WHITE)
                    }

                    val marker = Marker(mapView).apply {
                        position = point
                        icon = drawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = when (markerData.type) {
                            MapMarkerType.HIT -> "Hit"
                            MapMarkerType.ALMOST -> "Almost"
                        }
                    }
                    mapView.overlays.add(marker)
                }

                mapView.invalidate()
            },
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("...", fontSize = 12.sp, color = Color.Gray)
        }
    }
}
