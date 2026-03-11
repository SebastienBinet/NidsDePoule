package fr.nidsdepoule.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

/**
 * Seismograph-style G-force display over the last 30 seconds.
 *
 * Samples are positioned by their timestamp (smooth scrolling) and drawn
 * as a continuous antialiased filled area (upper half) + mirrored lower half.
 *
 * Color bands:
 *   - Green:  < 0.1 G  (road noise)
 *   - Yellow: 0.1 – 0.3 G  (small bump)
 *   - Orange: 0.3 – 0.6 G  (pothole)
 *   - Red:    >= 0.6 G  (big pothole)
 *
 * Hit markers are shown as bright red vertical lines with an arrow.
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

        // Time window: position each sample by its timestamp
        val tMin = samples.first().timestampMs
        val tMax = samples.last().timestampMs
        val tRange = (tMax - tMin).coerceAtLeast(1L).toFloat()

        // Find Y range — bars extend symmetrically from center
        var maxG = 0.5f  // minimum range: 0.5 G
        for (s in samples) {
            val g = s.magnitudeMg / 1000f
            if (g > maxG) maxG = g
        }
        maxG *= 1.1f  // 10% headroom

        val halfHeight = usableHeight / 2f

        // Map sample to screen X using its timestamp (smooth, sub-pixel)
        fun sampleX(s: AccelerationBuffer.Sample): Float =
            padding + ((s.timestampMs - tMin) / tRange) * usableWidth

        // Build upper and lower envelope paths for filled area
        val upperPath = Path()
        val lowerPath = Path()

        val firstX = sampleX(samples.first())
        val firstG = samples.first().magnitudeMg / 1000f
        val firstH = (firstG / maxG) * halfHeight

        upperPath.moveTo(firstX, centerY - firstH)
        lowerPath.moveTo(firstX, centerY + firstH)

        for (i in 1 until samples.size) {
            val s = samples[i]
            val x = sampleX(s)
            val g = s.magnitudeMg / 1000f
            val barH = (g / maxG) * halfHeight
            upperPath.lineTo(x, centerY - barH)
            lowerPath.lineTo(x, centerY + barH)
        }

        // Draw the envelope as colored stroke segments.
        // We draw per-segment lines colored by intensity for the gradient effect.
        for (i in 0 until samples.size - 1) {
            val s0 = samples[i]
            val s1 = samples[i + 1]
            val x0 = sampleX(s0)
            val x1 = sampleX(s1)
            val g0 = s0.magnitudeMg / 1000f
            val g1 = s1.magnitudeMg / 1000f
            val h0 = (g0 / maxG) * halfHeight
            val h1 = (g1 / maxG) * halfHeight
            val gMax = maxOf(g0, g1)

            val segColor = when {
                s0.isHit || s1.isHit -> Color(0xFFFF1744)
                gMax >= 0.6f -> Color(0xFFD32F2F)
                gMax >= 0.3f -> Color(0xFFFF9800)
                gMax >= 0.1f -> Color(0xFFFDD835)
                else -> Color(0xFF4CAF50)
            }

            // Upper half
            drawLine(
                color = segColor.copy(alpha = 0.9f),
                start = Offset(x0, centerY - h0),
                end = Offset(x1, centerY - h1),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
            // Lower half (mirror)
            drawLine(
                color = segColor.copy(alpha = 0.9f),
                start = Offset(x0, centerY + h0),
                end = Offset(x1, centerY + h1),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )

            // Fill between center and envelope
            val fillPath = Path().apply {
                moveTo(x0, centerY - h0)
                lineTo(x1, centerY - h1)
                lineTo(x1, centerY + h1)
                lineTo(x0, centerY + h0)
                close()
            }
            drawPath(fillPath, color = segColor.copy(alpha = 0.25f))
        }

        // Draw tick marks for peaks sent to server
        for (sample in samples) {
            if (sample.isPeakSent) {
                val x = sampleX(sample)
                val tickY = height - 2f
                drawLine(
                    color = Color(0xFFFF1744),
                    start = Offset(x, tickY - 10f),
                    end = Offset(x, tickY),
                    strokeWidth = 3f,
                )
                drawLine(
                    color = Color(0xFFFF1744),
                    start = Offset(x - 4f, tickY - 6f),
                    end = Offset(x, tickY - 10f),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFFFF1744),
                    start = Offset(x + 4f, tickY - 6f),
                    end = Offset(x, tickY - 10f),
                    strokeWidth = 2f,
                )
            }
        }

        // Draw scale labels
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 20f
            isAntiAlias = true
        }

        drawContext.canvas.nativeCanvas.drawText(
            "0",
            width - padding + 2,
            centerY + 6,
            paint,
        )

        val maxLabel = "%.1fG".format(maxG)
        drawContext.canvas.nativeCanvas.drawText(
            maxLabel,
            width - padding - 40,
            padding + 14,
            paint,
        )
    }
}
