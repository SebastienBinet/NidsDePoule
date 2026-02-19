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
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Compose Canvas that draws two acceleration traces (vertical + lateral)
 * over the last 60 seconds.
 *
 * Vertical acceleration: orange trace (shows pothole impacts)
 * Lateral acceleration: blue trace (shows swerving)
 *
 * The Y axis auto-scales to fit the data. The baseline (1g ≈ 1000mg)
 * is shown as a dashed gray line.
 */
@Composable
fun AccelerationGraph(
    samples: List<AccelerationBuffer.Sample>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        if (samples.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val padding = 8f

        // Find Y range
        var maxAbsVertical = 1500  // minimum range
        var maxAbsLateral = 500
        for (s in samples) {
            val av = abs(s.verticalMg)
            val al = abs(s.lateralMg)
            if (av > maxAbsVertical) maxAbsVertical = av
            if (al > maxAbsLateral) maxAbsLateral = al
        }
        val maxY = maxOf(maxAbsVertical, maxAbsLateral).toFloat() * 1.1f

        // Draw baseline (1g = 1000mg)
        val baselineY = height - padding - ((1000f / maxY) * (height - 2 * padding))
        drawDashedLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(padding, baselineY),
            end = Offset(width - padding, baselineY),
            dashLength = 8f,
            gapLength = 6f,
        )

        // Draw zero line
        val zeroY = height - padding
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(padding, zeroY),
            end = Offset(width - padding, zeroY),
            strokeWidth = 1f,
        )

        // Draw vertical acceleration trace (orange)
        drawTrace(
            samples = samples,
            extractValue = { it.verticalMg },
            color = Color(0xFFFF9800),
            maxY = maxY,
            width = width,
            height = height,
            padding = padding,
        )

        // Draw lateral acceleration trace (blue)
        drawTrace(
            samples = samples,
            extractValue = { it.lateralMg },
            color = Color(0xFF2196F3),
            maxY = maxY,
            width = width,
            height = height,
            padding = padding,
        )

        // Draw hit markers (red circles + vertical lines)
        drawHitMarkers(
            samples = samples,
            maxY = maxY,
            width = width,
            height = height,
            padding = padding,
        )
    }
}

private fun DrawScope.drawTrace(
    samples: List<AccelerationBuffer.Sample>,
    extractValue: (AccelerationBuffer.Sample) -> Int,
    color: Color,
    maxY: Float,
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
        val value = abs(extractValue(sample)).toFloat()
        val y = height - padding - (value / maxY) * usableHeight

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
    maxY: Float,
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
        val verticalY = height - padding - (abs(sample.verticalMg).toFloat() / maxY) * usableHeight

        // Translucent vertical line spanning the full graph height
        drawLine(
            color = hitColor.copy(alpha = 0.25f),
            start = Offset(x, padding),
            end = Offset(x, height - padding),
            strokeWidth = 3f,
        )

        // Red circle at the vertical acceleration value
        drawCircle(
            color = hitColor,
            radius = 6f,
            center = Offset(x, verticalY),
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
