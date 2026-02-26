package fr.nidsdepoule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nidsdepoule.app.sensor.VoiceCommandListener

/**
 * Dev-mode overlay showing real-time voice match scores for all 12 keywords.
 *
 * Each keyword shows a horizontal bar proportional to its similarity score,
 * colored by category (amber for Almost, red for Hit). Bright when score is
 * above the detection threshold.
 */
@Composable
fun VoiceMatchOverlay(
    matchScores: Map<String, Float>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Text(
            text = "Voice Match (dev)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Almost keywords
        for (kw in VoiceCommandListener.ALMOST_KEYWORDS) {
            val score = matchScores[kw] ?: 0f
            KeywordScoreBar(
                keyword = kw,
                score = score,
                baseColor = Color(0xFFFF8F00), // amber
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Hit keywords
        for (kw in VoiceCommandListener.HIT_KEYWORDS) {
            val score = matchScores[kw] ?: 0f
            KeywordScoreBar(
                keyword = kw,
                score = score,
                baseColor = Color(0xFFD32F2F), // red
            )
        }
    }
}

@Composable
private fun KeywordScoreBar(
    keyword: String,
    score: Float,
    baseColor: Color,
) {
    val isMatch = score > 0.5f
    val barColor = if (isMatch) baseColor else baseColor.copy(alpha = 0.4f)
    val textColor = if (isMatch) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keyword label (fixed width)
        Text(
            text = keyword,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.width(64.dp),
        )

        // Score bar
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = score.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor),
            )
        }

        // Score label
        Text(
            text = "%.0f".format(score * 100),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
