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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nidsdepoule.app.sensor.LocationReading
import fr.nidsdepoule.app.store.DevicePosStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.cos
import kotlin.math.max

/**
 * Type of map marker event.
 */
enum class MapMarkerType { HIT, ALMOST, SERVER }

/**
 * A map marker representing a Hit or Almost event.
 */
data class MapMarkerData(
    val latMicrodeg: Int,
    val lonMicrodeg: Int,
    val type: MapMarkerType,
    val timestampMs: Long,
)

/** How many seconds ahead of travel the map should cover. */
const val MAP_LOOKAHEAD_SECONDS = 60f

/** Montreal island bounding box (approx). */
private const val MONTREAL_MIN_LAT = 45.40
private const val MONTREAL_MAX_LAT = 45.72
private const val MONTREAL_MIN_LON = -73.98
private const val MONTREAL_MAX_LON = -73.47

/**
 * Lightweight route widget drawn with Compose Canvas + async OSM tiles.
 *
 * Features:
 * - Mercator projection for correct tile alignment
 * - Radius = current speed * MAP_LOOKAHEAD_SECONDS (min 100 m)
 * - 1:1 aspect ratio so the map is never distorted
 * - Touch to expand to 1 km radius, animated 1 s transition back
 * - Tiles fetched asynchronously by [OsmTileLoader]
 */
@Composable
fun RouteMapWidget(
    locationHistory: List<LocationReading>,
    markers: List<MapMarkerData>,
    devicePosStore: DevicePosStore? = null,
    currentSpeedMps: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    // Observe tile loader revision so we recompose when new tiles arrive
    val tileRevision by OsmTileLoader.revision

    // Radius based on speed: speed * 60s, minimum 100 m
    val speedRadiusM = max(currentSpeedMps * MAP_LOOKAHEAD_SECONDS, 100f)

    // Touch state: expand to Montreal island when touched
    var isTouched by remember { mutableStateOf(false) }

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

    // Animate between current-position view and Montreal island view
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * cos(Math.toRadians(curLat))
    val speedRadiusDegLat = speedRadiusM / metersPerDegLat
    val speedRadiusDegLon = if (metersPerDegLon > 0) speedRadiusM / metersPerDegLon else speedRadiusDegLat

    // Default view: centered on current position with speed-based radius
    val defaultMinLat = curLat - speedRadiusDegLat
    val defaultMaxLat = curLat + speedRadiusDegLat
    val defaultMinLon = curLon - speedRadiusDegLon
    val defaultMaxLon = curLon + speedRadiusDegLon

    // Animate bounds between default and Montreal island
    val animMinLat by animateFloatAsState(
        targetValue = if (isTouched) MONTREAL_MIN_LAT.toFloat() else defaultMinLat.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "minLat",
    )
    val animMaxLat by animateFloatAsState(
        targetValue = if (isTouched) MONTREAL_MAX_LAT.toFloat() else defaultMaxLat.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "maxLat",
    )
    val animMinLon by animateFloatAsState(
        targetValue = if (isTouched) MONTREAL_MIN_LON.toFloat() else defaultMinLon.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "minLon",
    )
    val animMaxLon by animateFloatAsState(
        targetValue = if (isTouched) MONTREAL_MAX_LON.toFloat() else defaultMaxLon.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "maxLon",
    )

    val minLat = animMinLat.toDouble()
    val maxLat = animMaxLat.toDouble()
    val minLon = animMinLon.toDouble()
    val maxLon = animMaxLon.toDouble()

    // Pick zoom level: find z where the viewport fits within ~5 tiles
    val z = run {
        var zoom = 18
        while (zoom > 2) {
            val tileCountX = OsmTileLoader.lonToTileX(maxLon, zoom) -
                    OsmTileLoader.lonToTileX(minLon, zoom) + 1
            val tileCountY = OsmTileLoader.latToTileY(minLat, zoom) -
                    OsmTileLoader.latToTileY(maxLat, zoom) + 1
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

    // Route bounding box in world pixels + 15% padding
    val routeWorldMinX = lonToWorldX(minLon)
    val routeWorldMaxX = lonToWorldX(maxLon)
    val routeWorldMinY = latToWorldY(maxLat)
    val routeWorldMaxY = latToWorldY(minLat)
    val routeWorldW = maxOf(routeWorldMaxX - routeWorldMinX, 10.0)
    val routeWorldH = maxOf(routeWorldMaxY - routeWorldMinY, 10.0)
    val padX = routeWorldW * 0.15
    val padY = routeWorldH * 0.15
    val rawW = routeWorldW + 2 * padX
    val rawH = routeWorldH + 2 * padY
    val centerWX = (routeWorldMinX + routeWorldMaxX) / 2.0
    val centerWY = (routeWorldMinY + routeWorldMaxY) / 2.0

    val textMeasurer = rememberTextMeasurer()

    // Collect device positions and overlay from store
    val devicePositions by (devicePosStore?.positions ?: MutableStateFlow(IntArray(0))).collectAsState()
    val overlayBitmap by (devicePosStore?.overlay ?: MutableStateFlow(null)).collectAsState()
    val overlayImageBitmap = remember(overlayBitmap) { overlayBitmap?.asImageBitmap() }

    val routeColor = Color(0xFF2196F3)
    val hitColor = Color(0xFFD32F2F)
    val almostColor = Color(0xFFFF8F00)
    val serverColor = Color(0xFF7B1FA2)
    val crossColor = Color(0xFF9E9E9E)
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

            // Enforce 1:1 aspect ratio: equal world-pixels per screen-pixel.
            // Scale the viewport so the data bounding box fits, then expand
            // the axis that has room to spare to match the screen ratio.
            val screenRatio = w / h  // > 1 if landscape
            val dataRatio = rawW / rawH
            val vpWorldW: Double
            val vpWorldH: Double
            if (dataRatio > screenRatio) {
                // Data is wider than screen: match width, expand height
                vpWorldW = rawW
                vpWorldH = rawW / screenRatio
            } else {
                // Data is taller than screen: match height, expand width
                vpWorldH = rawH
                vpWorldW = rawH * screenRatio
            }
            val vpWorldMinX = centerWX - vpWorldW / 2.0
            val vpWorldMinY = centerWY - vpWorldH / 2.0

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
                    MapMarkerType.SERVER -> serverColor.copy(alpha = alpha)
                }
                drawCircle(color = color, radius = 14f, center = pos)
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.8f),
                    radius = 14f,
                    center = pos,
                    style = Stroke(width = 2f),
                )
            }

            // Draw open-data pothole repairs
            // Three states:
            //   1. Pressed (Montreal overview) → low-res overlay bitmap
            //   2. Animating back → crosses from the stable interest zone
            //      (fixed set, only geoToScreen changes as viewport shrinks)
            //   3. Settled on interest zone → same crosses, now pixel-perfect
            if (isTouched) {
                // Montreal overview: draw pre-rendered low-res overlay
                val img = overlayImageBitmap
                if (img != null) {
                    val topLeft = geoToScreen(DevicePosStore.OVERLAY_MAX_LAT, DevicePosStore.OVERLAY_MIN_LON)
                    val bottomRight = geoToScreen(DevicePosStore.OVERLAY_MIN_LAT, DevicePosStore.OVERLAY_MAX_LON)
                    drawImage(
                        image = img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                        dstSize = IntSize(
                            (bottomRight.x - topLeft.x).toInt(),
                            (bottomRight.y - topLeft.y).toInt(),
                        ),
                    )
                }
            } else {
                // Not pressed (animating back or settled): draw crosses
                // from the stable interest zone (default bounds), NOT the
                // animated bounds. This keeps the cross count constant and
                // small regardless of the current animation frame.
                val crossC = crossColor.copy(alpha = 0.8f)
                val stableZ = run {
                    var sz = 18
                    while (sz > 2) {
                        val tcx = OsmTileLoader.lonToTileX(defaultMaxLon, sz) -
                                OsmTileLoader.lonToTileX(defaultMinLon, sz) + 1
                        val tcy = OsmTileLoader.latToTileY(defaultMinLat, sz) -
                                OsmTileLoader.latToTileY(defaultMaxLat, sz) + 1
                        if (tcx <= 5 && tcy <= 5) break
                        sz--
                    }
                    sz
                }
                val arm = when {
                    stableZ >= 15 -> 8f
                    stableZ >= 12 -> 5f
                    else -> 3f
                }
                val crossStrokeW = when {
                    stableZ >= 15 -> 2.5f
                    stableZ >= 12 -> 2f
                    else -> 1.5f
                }
                val stableMinLatMicro = (defaultMinLat * 1_000_000).toInt()
                val stableMaxLatMicro = (defaultMaxLat * 1_000_000).toInt()
                val stableMinLonMicro = (defaultMinLon * 1_000_000).toInt()
                val stableMaxLonMicro = (defaultMaxLon * 1_000_000).toInt()
                val (rangeStart, rangeEnd) = devicePosStore?.latRange(stableMinLatMicro, stableMaxLatMicro) ?: Pair(0, 0)
                var idx = rangeStart
                while (idx < rangeEnd) {
                    val lon = devicePositions[idx + 1]
                    if (lon in stableMinLonMicro..stableMaxLonMicro) {
                        // geoToScreen uses the *animated* viewport, so crosses
                        // move naturally with the zoom animation
                        val pos = geoToScreen(devicePositions[idx] / 1_000_000.0, lon / 1_000_000.0)
                        drawLine(crossC, Offset(pos.x - arm, pos.y - arm), Offset(pos.x + arm, pos.y + arm), strokeWidth = crossStrokeW, cap = StrokeCap.Round)
                        drawLine(crossC, Offset(pos.x - arm, pos.y + arm), Offset(pos.x + arm, pos.y - arm), strokeWidth = crossStrokeW, cap = StrokeCap.Round)
                    }
                    idx += 2
                }
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

            // Draw legend (in Montreal-island / touched view)
            if (isTouched) {
                val legendItems = listOf(
                    Triple(hitColor, "circle", "Nid-de-poule détecté"),
                    Triple(almostColor, "circle", "Presque !"),
                    Triple(serverColor, "circle", "Signalé (serveur)"),
                    Triple(crossColor, "cross", "Réparé en 2025"),
                    Triple(currentPosColor, "circle", "Position actuelle"),
                )
                val lineHeight = 18f
                val legendPadH = 8f
                val legendPadV = 6f
                val iconSize = 6f
                val iconTextGap = 8f
                val legendTextStyle = TextStyle(color = Color.White, fontSize = 10.sp)

                // Measure text widths to size the background
                val measuredTexts = legendItems.map { (_, _, label) ->
                    textMeasurer.measure(label, legendTextStyle)
                }
                val maxTextWidth = measuredTexts.maxOf { it.size.width }
                val legendW = legendPadH * 2 + iconSize * 2 + iconTextGap + maxTextWidth
                val legendH = legendPadV * 2 + legendItems.size * lineHeight

                val legendX = 8f
                val legendY = h - legendH - 8f

                // Background
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset(legendX, legendY),
                    size = androidx.compose.ui.geometry.Size(legendW, legendH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )

                // Items
                for ((idx, item) in legendItems.withIndex()) {
                    val (color, shape, _) = item
                    val iy = legendY + legendPadV + idx * lineHeight + lineHeight / 2f
                    val ix = legendX + legendPadH + iconSize

                    if (shape == "circle") {
                        drawCircle(color = color, radius = iconSize, center = Offset(ix, iy))
                    } else {
                        // Cross
                        val a = iconSize * 0.8f
                        drawLine(color, Offset(ix - a, iy - a), Offset(ix + a, iy + a), strokeWidth = 2f, cap = StrokeCap.Round)
                        drawLine(color, Offset(ix - a, iy + a), Offset(ix + a, iy - a), strokeWidth = 2f, cap = StrokeCap.Round)
                    }

                    drawText(
                        textMeasurer = textMeasurer,
                        text = measuredTexts[idx].layoutInput.text.toString(),
                        topLeft = Offset(ix + iconSize + iconTextGap, iy - measuredTexts[idx].size.height / 2f),
                        style = legendTextStyle,
                    )
                }
            }
        }
    }
}
