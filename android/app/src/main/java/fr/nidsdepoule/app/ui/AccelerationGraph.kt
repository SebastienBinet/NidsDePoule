package fr.nidsdepoule.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Seismograph-style G-force display over the last 30 seconds.
 *
 * At rest: a thin green horizontal line at the center.
 * On impact: vertical bars grow upward from center, colored by intensity:
 *   - Green:  < 0.1 G  (road noise)
 *   - Yellow: 0.1 - 0.3 G  (small bump)
 *   - Orange: 0.3 - 0.6 G  (pothole)
 *   - Red:    >= 0.6 G  (big pothole)
 *
 * Hit markers are shown as bright red bars with a circle.
 */
@Composable
fun AccelerationGraph(
    samples: List<AccelerationBuffer.Sample>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        val width = size.width
        val height = size.height
        val padding = 8f
        val usableWidth = width - 2 * padding
        val usableHeight = height - 2 * padding
        val centerY = padding + usableHeight / 2f

        // Draw center line (green baseline = no G)
        drawLine(
            color = Color(0xFF4CAF50).copy(alpha = 0.6f),
            start = Offset(padding, centerY),
            end = Offset(width - padding, centerY),
            strokeWidth = 2f,
        )

        if (samples.isEmpty()) return@Canvas

        // Find Y range — bars extend symmetrically from center
        var maxG = 0.5f  // minimum range: 0.5 G
        for (s in samples) {
            val g = s.magnitudeMg / 1000f
            if (g > maxG) maxG = g
        }
        maxG *= 1.1f  // 10% headroom

        val halfHeight = usableHeight / 2f
        val barWidth = if (samples.size > 1) {
            (usableWidth / samples.size.toFloat()).coerceIn(1f, 4f)
        } else 2f

        // Draw each sample as a vertical bar from center
        for ((i, sample) in samples.withIndex()) {
            val x = padding + (i.toFloat() / samples.size) * usableWidth
            val g = sample.magnitudeMg / 1000f
            val barHalfLen = (g / maxG) * halfHeight

            val barColor = when {
                sample.isHit -> Color(0xFFFF1744)  // bright red for detected hits
                g >= 0.6f -> Color(0xFFD32F2F)     // red
                g >= 0.3f -> Color(0xFFFF9800)     // orange
                g >= 0.1f -> Color(0xFFFDD835)     // yellow
                else -> Color(0xFF4CAF50)           // green
            }

            // Draw bar symmetrically from center
            drawLine(
                color = barColor,
                start = Offset(x, centerY - barHalfLen),
                end = Offset(x, centerY + barHalfLen),
                strokeWidth = barWidth,
            )
        }

        // Draw scale labels
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 20f
            isAntiAlias = true
        }

        // "0" at center-right
        drawContext.canvas.nativeCanvas.drawText(
            "0",
            width - padding + 2,
            centerY + 6,
            paint,
        )

        // Max G label at top
        val maxLabel = "%.1fG".format(maxG)
        drawContext.canvas.nativeCanvas.drawText(
            maxLabel,
            width - padding - 40,
            padding + 14,
            paint,
        )
    }
}
