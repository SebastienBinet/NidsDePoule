package fr.nidsdepoule.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

/**
 * Compose Canvas that draws acceleration magnitude over the last 30 seconds.
 *
 * Shows a single orange trace of G-force magnitude (orientation-independent).
 * At rest the value is ~0 G. A pothole hit shows as a spike (e.g., 0.3-1.5 G).
 *
 * Dashed gray line at 0.3 G marks a reference threshold.
 */
@Composable
fun AccelerationGraph(
    samples: List<AccelerationBuffer.Sample>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        if (samples.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val padding = 8f

        // Find Y range in G (magnitude_mg / 1000)
        var maxMagnitudeG = 0.5f  // minimum range: 0.5 G
        for (s in samples) {
            val g = s.magnitudeMg / 1000f
            if (g > maxMagnitudeG) maxMagnitudeG = g
        }
        maxMagnitudeG *= 1.1f  // 10% headroom

        // Draw zero line at bottom
        val zeroY = height - padding
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(padding, zeroY),
            end = Offset(width - padding, zeroY),
            strokeWidth = 1f,
        )

        // Draw reference line at 0.3 G
        val refG = 0.3f
        if (refG < maxMagnitudeG) {
            val refY = height - padding - (refG / maxMagnitudeG) * (height - 2 * padding)
            drawDashedLine(
                color = Color.Gray.copy(alpha = 0.5f),
                start = Offset(padding, refY),
                end = Offset(width - padding, refY),
                dashLength = 8f,
                gapLength = 6f,
            )
            // Label "0.3G"
            drawContext.canvas.nativeCanvas.drawText(
                "0.3G",
                padding + 2,
                refY - 4,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 22f
                    isAntiAlias = true
                }
            )
        }

        // Draw magnitude trace (orange)
        drawMagnitudeTrace(
            samples = samples,
            color = Color(0xFFFF9800),
            maxG = maxMagnitudeG,
            width = width,
            height = height,
            padding = padding,
        )

        // Draw hit markers (red circles + vertical lines)
        drawHitMarkers(
            samples = samples,
            maxG = maxMagnitudeG,
            width = width,
            height = height,
            padding = padding,
        )
    }
}

private fun DrawScope.drawMagnitudeTrace(
    samples: List<AccelerationBuffer.Sample>,
    color: Color,
    maxG: Float,
    width: Float,
    height: Float,
    padding: Float,
) {
    if (samples.size < 2) return

    val path = Path()
    val usableWidth = width - 2 * padding
    val usableHeight = height - 2 * padding
    val xStep = usableWidth / (samples.size - 1).toFloat()

    for ((i, sample) in samples.withIndex()) {
        val x = padding + i * xStep
        val g = sample.magnitudeMg / 1000f
        val y = height - padding - (g / maxG) * usableHeight

        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2f),
    )
}

private fun DrawScope.drawHitMarkers(
    samples: List<AccelerationBuffer.Sample>,
    maxG: Float,
    width: Float,
    height: Float,
    padding: Float,
) {
    if (samples.size < 2) return

    val usableWidth = width - 2 * padding
    val usableHeight = height - 2 * padding
    val xStep = usableWidth / (samples.size - 1).toFloat()
    val hitColor = Color(0xFFFF1744)  // bright red

    for ((i, sample) in samples.withIndex()) {
        if (!sample.isHit) continue

        val x = padding + i * xStep
        val g = sample.magnitudeMg / 1000f
        val markerY = height - padding - (g / maxG) * usableHeight

        // Translucent vertical line spanning the full graph height
        drawLine(
            color = hitColor.copy(alpha = 0.25f),
            start = Offset(x, padding),
            end = Offset(x, height - padding),
            strokeWidth = 3f,
        )

        // Red circle at the magnitude value
        drawCircle(
            color = hitColor,
            radius = 6f,
            center = Offset(x, markerY),
        )
    }
}

private fun DrawScope.drawDashedLine(
    color: Color,
    start: Offset,
    end: Offset,
    dashLength: Float,
    gapLength: Float,
) {
    val dx = end.x - start.x
    val totalLength = dx
    var x = start.x
    var drawing = true

    while (x < start.x + totalLength) {
        val segmentEnd = if (drawing) {
            minOf(x + dashLength, start.x + totalLength)
        } else {
            minOf(x + gapLength, start.x + totalLength)
        }

        if (drawing) {
            drawLine(
                color = color,
                start = Offset(x, start.y),
                end = Offset(segmentEnd, start.y),
                strokeWidth = 1f,
            )
        }

        x = segmentEnd
        drawing = !drawing
    }
}
