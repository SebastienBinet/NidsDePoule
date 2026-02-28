package fr.nidsdepoule.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nidsdepoule.app.sensor.LocationReading
import kotlin.math.cos

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
 * Lightweight route widget drawn with Compose Canvas + async OSM tiles.
 *
 * Features:
 * - Mercator projection for correct tile alignment
 * - Minimum 100m radius around current position
 * - Touch to expand to 1km radius, animated 1s transition back
 * - Tiles fetched asynchronously by [OsmTileLoader]
 */
@Composable
fun RouteMapWidget(
    locationHistory: List<LocationReading>,
    markers: List<MapMarkerData>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    // Observe tile loader revision so we recompose when new tiles arrive
    val tileRevision by OsmTileLoader.revision

    // Touch state: expand to 1km when touched
    var isTouched by remember { mutableStateOf(false) }
    val minRadiusM by animateFloatAsState(
        targetValue = if (isTouched) 1000f else 100f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "minRadius",
    )

    // Flash animation for recent markers
    val infiniteTransition = rememberInfiniteTransition(label = "markerFlash")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flashAlpha",
    )

    if (locationHistory.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(shape)
                .background(Color(0xFF1B1B2F)),
            contentAlignment = Alignment.Center,
        ) {
            Text("En attente du GPS\u2026", fontSize = 12.sp, color = Color(0xFF888888))
        }
        return
    }

    // Current position (last GPS reading)
    val curLat = locationHistory.last().latMicrodeg / 1_000_000.0
    val curLon = locationHistory.last().lonMicrodeg / 1_000_000.0

    // Collect all points (route + markers) in degrees
    val allLats = locationHistory.map { it.latMicrodeg / 1_000_000.0 } +
            markers.map { it.latMicrodeg / 1_000_000.0 }
    val allLons = locationHistory.map { it.lonMicrodeg / 1_000_000.0 } +
            markers.map { it.lonMicrodeg / 1_000_000.0 }

    var minLat = allLats.min()
    var maxLat = allLats.max()
    var minLon = allLons.min()
    var maxLon = allLons.max()

    // Enforce minimum radius around current position
    // 1 degree latitude ≈ 111,320 meters
    // 1 degree longitude ≈ 111,320 * cos(lat) meters
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * cos(Math.toRadians(curLat))
    val minRadiusDegLat = minRadiusM / metersPerDegLat
    val minRadiusDegLon = if (metersPerDegLon > 0) minRadiusM / metersPerDegLon else minRadiusDegLat

    // Expand bounds to ensure minimum radius around current position
    minLat = minOf(minLat, curLat - minRadiusDegLat)
    maxLat = maxOf(maxLat, curLat + minRadiusDegLat)
    minLon = minOf(minLon, curLon - minRadiusDegLon)
    maxLon = maxOf(maxLon, curLon + minRadiusDegLon)

    // Pick zoom level: find z where the viewport fits within ~5 tiles
    val z = run {
        var zoom = 18
        while (zoom > 2) {
            val tileCountX = OsmTileLoader.lonToTileX(maxLon, zoom) -
                    OsmTileLoader.lonToTileX(minLon, zoom) + 1
            val tileCountY = OsmTileLoader.latToTileY(maxLat, zoom) -
                    OsmTileLoader.latToTileY(minLat, zoom) + 1
            if (tileCountX <= 5 && tileCountY <= 5) break
            zoom--
        }
        zoom
    }

    // Compute tile range (with 1-tile padding)
    val minTileX = OsmTileLoader.lonToTileX(minLon, z) - 1
    val maxTileX = OsmTileLoader.lonToTileX(maxLon, z) + 1
    val minTileY = OsmTileLoader.latToTileY(maxLat, z) - 1
    val maxTileY = OsmTileLoader.latToTileY(minLat, z) + 1

    // World pixel coordinates at this zoom level
    val n = (1 shl z).toDouble()
    fun lonToWorldX(lon: Double): Double = (lon + 180.0) / 360.0 * n * 256.0
    fun latToWorldY(lat: Double): Double {
        val latRad = Math.toRadians(lat)
        return (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n * 256.0
    }

    // Route bounding box in world pixels + 20% padding
    val routeWorldMinX = lonToWorldX(minLon)
    val routeWorldMaxX = lonToWorldX(maxLon)
    val routeWorldMinY = latToWorldY(maxLat)
    val routeWorldMaxY = latToWorldY(minLat)
    val routeWorldW = maxOf(routeWorldMaxX - routeWorldMinX, 10.0)
    val routeWorldH = maxOf(routeWorldMaxY - routeWorldMinY, 10.0)
    val padX = routeWorldW * 0.15
    val padY = routeWorldH * 0.15
    val vpWorldMinX = routeWorldMinX - padX
    val vpWorldMaxX = routeWorldMaxX + padX
    val vpWorldMinY = routeWorldMinY - padY
    val vpWorldMaxY = routeWorldMaxY + padY
    val vpWorldW = vpWorldMaxX - vpWorldMinX
    val vpWorldH = vpWorldMaxY - vpWorldMinY

    val routeColor = Color(0xFF2196F3)
    val hitColor = Color(0xFFD32F2F)
    val almostColor = Color(0xFFFF8F00)
    val currentPosColor = Color(0xFF4CAF50)
    val bgColor = Color(0xFF1B1B2F)
    val now = System.currentTimeMillis()

    @Suppress("UNUSED_EXPRESSION")
    tileRevision

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isTouched = true
                        tryAwaitRelease()
                        isTouched = false
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            fun worldToScreen(worldX: Double, worldY: Double): Offset {
                val sx = ((worldX - vpWorldMinX) / vpWorldW * w).toFloat()
                val sy = ((worldY - vpWorldMinY) / vpWorldH * h).toFloat()
                return Offset(sx, sy)
            }

            fun geoToScreen(latDeg: Double, lonDeg: Double): Offset {
                return worldToScreen(lonToWorldX(lonDeg), latToWorldY(latDeg))
            }

            // Draw tiles
            for (ty in minTileY..maxTileY) {
                for (tx in minTileX..maxTileX) {
                    val tile = OsmTileLoader.getTile(z, tx, ty)
                    if (tile != null) {
                        val tileTopLeft = worldToScreen(tx * 256.0, ty * 256.0)
                        val tileBR = worldToScreen((tx + 1) * 256.0, (ty + 1) * 256.0)
                        drawImage(
                            image = tile,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(tile.width, tile.height),
                            dstOffset = IntOffset(tileTopLeft.x.toInt(), tileTopLeft.y.toInt()),
                            dstSize = IntSize(
                                (tileBR.x - tileTopLeft.x).toInt(),
                                (tileBR.y - tileTopLeft.y).toInt(),
                            ),
                        )
                    }
                }
            }

            // Draw route polyline
            if (locationHistory.size >= 2) {
                val path = Path()
                val first = geoToScreen(
                    locationHistory[0].latMicrodeg / 1_000_000.0,
                    locationHistory[0].lonMicrodeg / 1_000_000.0,
                )
                path.moveTo(first.x, first.y)
                for (i in 1 until locationHistory.size) {
                    val pt = geoToScreen(
                        locationHistory[i].latMicrodeg / 1_000_000.0,
                        locationHistory[i].lonMicrodeg / 1_000_000.0,
                    )
                    path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path,
                    color = Color.White.copy(alpha = 0.5f),
                    style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path,
                    color = routeColor.copy(alpha = 0.9f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Draw markers
            for (m in markers) {
                val pos = geoToScreen(
                    m.latMicrodeg / 1_000_000.0,
                    m.lonMicrodeg / 1_000_000.0,
                )
                val isRecent = now - m.timestampMs < 10_000
                val alpha = if (isRecent) flashAlpha else 0.8f
                val color = when (m.type) {
                    MapMarkerType.HIT -> hitColor.copy(alpha = alpha)
                    MapMarkerType.ALMOST -> almostColor.copy(alpha = alpha)
                }
                drawCircle(color = color, radius = 14f, center = pos)
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.8f),
                    radius = 14f,
                    center = pos,
                    style = Stroke(width = 2f),
                )
            }

            // Draw current position
            val curPos = geoToScreen(curLat, curLon)
            drawCircle(color = currentPosColor, radius = 8f, center = curPos)
            drawCircle(
                color = Color.White,
                radius = 8f,
                center = curPos,
                style = Stroke(width = 2f),
            )
        }
    }
}
