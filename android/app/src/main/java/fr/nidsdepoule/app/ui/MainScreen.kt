package fr.nidsdepoule.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nidsdepoule.app.R
import fr.nidsdepoule.app.sensor.LocationReading
import fr.nidsdepoule.app.sensor.audio.MfccExtractor
import fr.nidsdepoule.app.sensor.audio.VoiceProfileStore

/**
 * Main screen of the NidsDePoule app.
 *
 * Shows:
 * 1. Status bar (GPS, connection)
 * 2. Two big report buttons (Almost / Hit)
 * 3. Acceleration graph (last 30 seconds)
 * 4. Data usage stats
 * 5. Hit counter
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    accelSamples: List<AccelerationBuffer.Sample>,
    hasGpsFix: Boolean,
    isConnected: Boolean,
    hitsDetected: Int,
    hitsSent: Int,
    hitsPending: Int,
    kbLastMinute: Float,
    mbLastHour: Float,
    mbThisMonth: Float,
    appVersion: String,
    buildTime: String,
    devModeEnabled: Boolean,
    onDevModeTap: () -> Unit,
    onAlmost: () -> Unit = {},
    onHit: () -> Unit = {},
    hitFlashActive: Boolean = false,
    hitFlashText: String = "HIT!",
    isSimulating: Boolean = false,
    onToggleSimulation: () -> Unit = {},
    serverUrl: String = "",
    voiceMuted: Boolean = false,
    onToggleVoice: () -> Unit = {},
    isListening: Boolean = false,
    // Map
    locationHistory: List<LocationReading> = emptyList(),
    mapMarkers: List<MapMarkerData> = emptyList(),
    // Voice training
    showVoiceTraining: Boolean = false,
    voiceTrainingKeywords: List<String> = emptyList(),
    voiceTrainingLabel: String = "",
    onAlmostLongPress: () -> Unit = {},
    onHitLongPress: () -> Unit = {},
    onVoiceTrainingDismiss: () -> Unit = {},
    onVoiceTrainingComplete: () -> Unit = {},
    profileStore: VoiceProfileStore? = null,
    mfccExtractor: MfccExtractor? = null,
    // Voice match overlay (dev mode)
    voiceMatchScores: Map<String, Float> = emptyMap(),
) {
    // Voice training dialog
    if (showVoiceTraining && profileStore != null && mfccExtractor != null) {
        VoiceTrainingDialog(
            keywords = voiceTrainingKeywords,
            groupLabel = voiceTrainingLabel,
            profileStore = profileStore,
            mfccExtractor = mfccExtractor,
            onDismiss = onVoiceTrainingDismiss,
            onComplete = onVoiceTrainingComplete,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Title with deploy canary and voice toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "v6 TEAL",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xFF009688), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            // Voice mute/unmute toggle
            TextButton(onClick = onToggleVoice) {
                Text(
                    text = if (voiceMuted) "MUTE" else "VOICE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (voiceMuted) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    } else {
                        Color(0xFF4CAF50)
                    },
                    modifier = Modifier
                        .background(
                            if (voiceMuted) MaterialTheme.colorScheme.surfaceVariant
                            else Color(0xFF4CAF50).copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status indicators
        StatusBar(
            hasGpsFix = hasGpsFix,
            isConnected = isConnected,
            devModeEnabled = devModeEnabled,
            isSimulating = isSimulating,
            isListening = isListening,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Two big report buttons: Almost and Hit
        // Tap = report. Long-press = voice training.
        ReportButtonsPanel(
            onAlmost = onAlmost,
            onHit = onHit,
            onAlmostLongPress = onAlmostLongPress,
            onHitLongPress = onHitLongPress,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Map widget (above the graph)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Route (last 30s)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                RouteMapWidget(
                    locationHistory = locationHistory,
                    markers = mapMarkers,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Acceleration graph
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.acceleration_graph),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                AccelerationGraph(
                    samples = accelSamples,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Data usage
        DataUsageCard(
            kbLastMinute = kbLastMinute,
            mbLastHour = mbLastHour,
            mbThisMonth = mbThisMonth,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Hit counter
        HitCounterCard(
            hitsDetected = hitsDetected,
            hitsSent = hitsSent,
            hitsPending = hitsPending,
        )

        // Dev mode controls (voice match overlay + simulation + server URL)
        if (devModeEnabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Voice match overlay
            if (voiceMatchScores.isNotEmpty()) {
                VoiceMatchOverlay(matchScores = voiceMatchScores)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onToggleSimulation,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSimulating) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (isSimulating) "Stop Simulation" else "Simulate (Cemetery Circuit)",
                    fontSize = 13.sp,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Server URL — read-only, tap to copy
            val clipboardManager = LocalClipboardManager.current
            Text(
                text = "Server: $serverUrl",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { clipboardManager.setText(AnnotatedString(serverUrl)) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Version number (tap 7 times for dev mode)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = onDevModeTap) {
                Text(
                    text = "v$appVersion - $buildTime",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }

    // Full-screen flash overlay when a report is sent
    AnimatedVisibility(
        visible = hitFlashActive,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x55FF1744)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = hitFlashText,
                fontSize = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
    }
    } // end Box
}

@Composable
private fun StatusBar(
    hasGpsFix: Boolean,
    isConnected: Boolean,
    devModeEnabled: Boolean,
    isSimulating: Boolean = false,
    isListening: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            label = stringResource(R.string.status_gps),
            active = hasGpsFix,
        )
        StatusChip(
            label = stringResource(R.string.status_connected),
            active = isConnected,
        )
        if (isListening) {
            Text(
                text = "MIC",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xFF2196F3), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (devModeEnabled) {
            Text(
                text = "DEV",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (isSimulating) {
            Text(
                text = "SIM",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    val bgColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Text(
        text = label,
        fontSize = 12.sp,
        color = textColor,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun DataUsageCard(
    kbLastMinute: Float,
    mbLastHour: Float,
    mbThisMonth: Float,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.data_usage),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DataStat(label = stringResource(R.string.last_minute), value = "%.1f KB".format(kbLastMinute))
                DataStat(label = stringResource(R.string.last_hour), value = "%.2f MB".format(mbLastHour))
                DataStat(label = stringResource(R.string.this_month), value = "%.1f MB".format(mbThisMonth))
            }
        }
    }
}

@Composable
private fun DataStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun HitCounterCard(
    hitsDetected: Int,
    hitsSent: Int,
    hitsPending: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DataStat(label = stringResource(R.string.hits_detected), value = "$hitsDetected")
            DataStat(label = stringResource(R.string.hits_sent), value = "$hitsSent")
            DataStat(label = stringResource(R.string.hits_pending), value = "$hitsPending")
        }
    }
}

/**
 * Two big buttons for pothole reporting.
 *
 * Layout:  [ iiiiiiiii !!! ] [ AYOYE !?!#$! ]
 *            Almost (amber)    Hit (red)
 *
 * Tap = report. Long-press = voice training for that category.
 * Buttons are intentionally large so the driver can tap without looking.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportButtonsPanel(
    onAlmost: () -> Unit,
    onHit: () -> Unit,
    onAlmostLongPress: () -> Unit = {},
    onHitLongPress: () -> Unit = {},
) {
    val btnHeight = 72.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Almost button (amber) — long-press for voice training
        Box(
            modifier = Modifier
                .weight(1f)
                .height(btnHeight)
                .background(Color(0xFFFF8F00), RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onAlmost,
                    onLongClick = onAlmostLongPress,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.btn_almost),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.btn_almost_hint),
                    fontSize = 9.sp,
                    color = Color.White,
                )
            }
        }
        // Hit button (red) — long-press for voice training
        Box(
            modifier = Modifier
                .weight(1f)
                .height(btnHeight)
                .background(Color(0xFFD32F2F), RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onHit,
                    onLongClick = onHitLongPress,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.btn_hit),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.btn_hit_hint),
                    fontSize = 9.sp,
                    color = Color.White,
                )
            }
        }
    }
}
