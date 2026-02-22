package fr.nidsdepoule.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nidsdepoule.app.R
import fr.nidsdepoule.app.reporting.HitReporter

/**
 * Main screen of the NidsDePoule app.
 *
 * Shows:
 * 1. Status bar (car mount, GPS, connection)
 * 2. Acceleration graph (last 60 seconds)
 * 3. Data usage stats (KB/min, MB/hour, MB/month)
 * 4. Reporting mode toggle (real-time vs Wi-Fi batch)
 * 5. Hit counter
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    accelSamples: List<AccelerationBuffer.Sample>,
    isMounted: Boolean,
    hasGpsFix: Boolean,
    isConnected: Boolean,
    reportingMode: HitReporter.Mode,
    onModeChanged: (HitReporter.Mode) -> Unit,
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
    onVisualSmall: () -> Unit = {},
    onVisualBig: () -> Unit = {},
    onImpactSmall: () -> Unit = {},
    onImpactBig: () -> Unit = {},
    hitFlashActive: Boolean = false,
    hitFlashText: String = "HIT!",
    isSimulating: Boolean = false,
    onToggleSimulation: () -> Unit = {},
    serverUrl: String = "",
    onServerUrlChanged: (String) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Title with deploy canary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "v5 PURPLE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xFF9C27B0), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status indicators
        StatusBar(
            isMounted = isMounted,
            hasGpsFix = hasGpsFix,
            isConnected = isConnected,
            devModeEnabled = devModeEnabled,
            isSimulating = isSimulating,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Manual report buttons — large targets for in-car use
        ReportButtonsPanel(
            onVisualSmall = onVisualSmall,
            onVisualBig = onVisualBig,
            onImpactSmall = onImpactSmall,
            onImpactBig = onImpactBig,
        )

        Spacer(modifier = Modifier.height(12.dp))

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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LegendDot(color = androidx.compose.ui.graphics.Color(0xFFFF9800), label = stringResource(R.string.vertical))
                    LegendDot(color = androidx.compose.ui.graphics.Color(0xFF2196F3), label = stringResource(R.string.lateral))
                }

                Spacer(modifier = Modifier.height(4.dp))
                AccelerationGraph(
                    samples = accelSamples,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Reporting mode — greyed out single line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${stringResource(R.string.reporting_mode)}: ${stringResource(R.string.mode_realtime)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
            Text(
                text = stringResource(R.string.future_feature),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
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

        // Dev mode controls (simulation + server URL)
        if (devModeEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
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
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

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

    // Full-screen red flash overlay when a hit is detected
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
    isMounted: Boolean,
    hasGpsFix: Boolean,
    isConnected: Boolean,
    devModeEnabled: Boolean,
    isSimulating: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            label = stringResource(R.string.status_car_mount),
            active = isMounted,
        )
        StatusChip(
            label = stringResource(R.string.status_gps),
            active = hasGpsFix,
        )
        StatusChip(
            label = stringResource(R.string.status_connected),
            active = isConnected,
        )
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
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp)
    }
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
 * Four big buttons for manual pothole reporting.
 *
 * Layout:  [  Hiii !  ] [ HIIIIIII !!! ]
 *          [  Ouch !  ] [  AYOYE !     ]
 *
 * Top row  = "I see a pothole" (visual, no accelerometer data).
 * Bottom row = "I just hit a pothole" (captures last 5 s of accel data).
 *
 * Buttons are intentionally large so the driver can tap without looking.
 */
@Composable
private fun ReportButtonsPanel(
    onVisualSmall: () -> Unit,
    onVisualBig: () -> Unit,
    onImpactSmall: () -> Unit,
    onImpactBig: () -> Unit,
) {
    // Colors: yellow tones for visual (seeing), red/orange tones for impact (hitting)
    val visualSmallColor = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFFDD835),  // yellow
        contentColor = Color(0xFF212121),
    )
    val visualBigColor = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFFF8F00),  // amber
        contentColor = Color.White,
    )
    val impactSmallColor = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFFF7043),  // deep orange
        contentColor = Color.White,
    )
    val impactBigColor = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFD32F2F),  // red
        contentColor = Color.White,
    )

    val btnHeight = 56.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Top row: visual reports ("I see it")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onVisualSmall,
                modifier = Modifier
                    .weight(1f)
                    .height(btnHeight),
                colors = visualSmallColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.btn_visual_small),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.btn_visual_small_hint),
                        fontSize = 9.sp,
                    )
                }
            }
            Button(
                onClick = onVisualBig,
                modifier = Modifier
                    .weight(1f)
                    .height(btnHeight),
                colors = visualBigColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.btn_visual_big),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = stringResource(R.string.btn_visual_big_hint),
                        fontSize = 9.sp,
                    )
                }
            }
        }

        // Bottom row: impact reports ("I hit it")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onImpactSmall,
                modifier = Modifier
                    .weight(1f)
                    .height(btnHeight),
                colors = impactSmallColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.btn_impact_small),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.btn_impact_small_hint),
                        fontSize = 9.sp,
                    )
                }
            }
            Button(
                onClick = onImpactBig,
                modifier = Modifier
                    .weight(1f)
                    .height(btnHeight),
                colors = impactBigColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.btn_impact_big),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = stringResource(R.string.btn_impact_big_hint),
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}
