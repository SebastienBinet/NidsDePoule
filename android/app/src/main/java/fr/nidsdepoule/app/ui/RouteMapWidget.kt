package fr.nidsdepoule.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nidsdepoule.app.sensor.LocationReading

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
 * Lightweight route widget drawn with Compose Canvas.
 *
 * Replaces osmdroid MapView which caused ANR due to heavy SQLite I/O
 * in its constructor. This pure-Compose version has zero native Views,
 * zero I/O, and zero ANR risk.
 *
 * - Dark background with grid lines
 * - Blue polyline for the route (last 30s)
 * - Red circle for Hit, amber circle for Almost
 * - Current position shown as a blue dot
 */
@Composable
fun RouteMapWidget(
    locationHistory: List<LocationReading>,
    markers: List<MapMarkerData>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

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

    // Compute bounds from location history + markers
    val allLats = locationHistory.map { it.latMicrodeg } +
            markers.map { it.latMicrodeg }
    val allLons = locationHistory.map { it.lonMicrodeg } +
            markers.map { it.lonMicrodeg }

    val minLat = allLats.min()
    val maxLat = allLats.max()
    val minLon = allLons.min()
    val maxLon = allLons.max()

    // Add padding so the route doesn't touch the edges
    val latSpan = maxOf((maxLat - minLat), 200) // at least ~20m
    val lonSpan = maxOf((maxLon - minLon), 200)
    val padLat = (latSpan * 0.2f).toInt()
    val padLon = (lonSpan * 0.2f).toInt()

    val viewMinLat = minLat - padLat
    val viewMaxLat = maxLat + padLat
    val viewMinLon = minLon - padLon
    val viewMaxLon = maxLon + padLon
    val viewLatSpan = (viewMaxLat - viewMinLat).toFloat()
    val viewLonSpan = (viewMaxLon - viewMinLon).toFloat()

    val routeColor = Color(0xFF2196F3)
    val hitColor = Color(0xFFD32F2F)
    val almostColor = Color(0xFFFF8F00)
    val gridColor = Color(0xFF2A2A40)
    val currentPosColor = Color(0xFF4CAF50)
    val now = System.currentTimeMillis()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .background(Color(0xFF1B1B2F)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            fun toScreen(latMicro: Int, lonMicro: Int): Offset {
                val x = if (viewLonSpan > 0) ((lonMicro - viewMinLon) / viewLonSpan) * w else w / 2
                // Lat is inverted (higher lat = higher on screen)
                val y = if (viewLatSpan > 0) (1f - (latMicro - viewMinLat) / viewLatSpan) * h else h / 2
                return Offset(x, y)
            }

            // Draw grid
            for (i in 1..4) {
                val gy = h * i / 5f
                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }
            for (i in 1..4) {
                val gx = w * i / 5f
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }

            // Draw route polyline
            if (locationHistory.size >= 2) {
                val path = Path()
                val first = toScreen(locationHistory[0].latMicrodeg, locationHistory[0].lonMicrodeg)
                path.moveTo(first.x, first.y)
                for (i in 1 until locationHistory.size) {
                    val pt = toScreen(locationHistory[i].latMicrodeg, locationHistory[i].lonMicrodeg)
                    path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path,
                    color = routeColor.copy(alpha = 0.8f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Draw markers
            for (m in markers) {
                val pos = toScreen(m.latMicrodeg, m.lonMicrodeg)
                val isRecent = now - m.timestampMs < 10_000
                val alpha = if (isRecent) flashAlpha else 0.8f
                val color = when (m.type) {
                    MapMarkerType.HIT -> hitColor.copy(alpha = alpha)
                    MapMarkerType.ALMOST -> almostColor.copy(alpha = alpha)
                }
                // Outer ring
                drawCircle(color = color, radius = 14f, center = pos)
                // White border
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.8f),
                    radius = 14f,
                    center = pos,
                    style = Stroke(width = 2f),
                )
            }

            // Draw current position (last point = green dot)
            val last = locationHistory.last()
            val curPos = toScreen(last.latMicrodeg, last.lonMicrodeg)
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
