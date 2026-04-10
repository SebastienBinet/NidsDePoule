package fr.nidsdepoule.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import fr.nidsdepoule.capture.CaptureViewModel
import fr.nidsdepoule.capture.BuildConfig
import fr.nidsdepoule.capture.recording.SessionSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(viewModel: CaptureViewModel) {
    val isRecording by viewModel.isRecording.collectAsState()
    val accelHz by viewModel.accelHz.collectAsState()
    val gyroHz by viewModel.gyroHz.collectAsState()
    val magHz by viewModel.magHz.collectAsState()
    val gpsHz by viewModel.gpsHz.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val eventCount by viewModel.eventCount.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val isStopping by viewModel.isStopping.collectAsState()
    val showStopDialog by viewModel.showStopDialog.collectAsState()
    val lastSessionId by viewModel.lastSessionId.collectAsState()
    val lastDurationMs by viewModel.lastDurationMs.collectAsState()
    val lastEventCounts by viewModel.lastEventCounts.collectAsState()
    val suggestedOrigin by viewModel.suggestedOrigin.collectAsState()
    val suggestedDestination by viewModel.suggestedDestination.collectAsState()
    val pendingShareIntent by viewModel.shareIntent.collectAsState()
    val lastKeyInfo by viewModel.lastKeyInfo.collectAsState()

    // Launch share sheet when intent is ready
    val context = LocalContext.current
    LaunchedEffect(pendingShareIntent) {
        pendingShareIntent?.let { intent ->
            context.startActivity(android.content.Intent.createChooser(intent, "Share session"))
            viewModel.clearShareIntent()
        }
    }

    // Post-capture annotation dialog
    if (showStopDialog) {
        StopSessionDialog(
            sessionId = lastSessionId,
            durationMs = lastDurationMs,
            eventCounts = lastEventCounts,
            suggestedOrigin = suggestedOrigin,
            suggestedDestination = suggestedDestination,
            onConfirm = { annotation ->
                viewModel.annotateSession(
                    routeOrigin = annotation.routeOrigin,
                    routeDestination = annotation.routeDestination,
                    labelingMethod = annotation.labelingMethod,
                    labelingReliability = annotation.labelingReliability,
                    comment = annotation.comment,
                )
            },
            onDismiss = { viewModel.dismissStopDialog() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sensor Capture")
                        Text(
                            "${BuildConfig.VERSION_LABEL} — ${BuildConfig.BUILD_TIME}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status card
            StatusCard(isRecording, durationMs, totalBytes, eventCount)

            Spacer(modifier = Modifier.height(12.dp))

            // Sensor rates card
            SensorRatesCard(accelHz, gyroHz, magHz, gpsHz)

            // Last BT key debug info
            if (lastKeyInfo.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                val isMapped = !lastKeyInfo.contains("not mapped")
                Text(
                    "BT: $lastKeyInfo",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isMapped) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Event marking buttons (for passenger use — no parasitic accel on separate phone)
            if (isRecording) {
                EventButtonsCard(
                    onPothole = { viewModel.recordEvent("pothole", "screen_button") },
                    onCrack = { viewModel.recordEvent("crack", "screen_button") },
                    onRough = { viewModel.recordEvent("rough", "screen_button") },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Start/Stop button
            Button(
                onClick = {
                    if (isStopping) { /* ignore clicks while stopping */ }
                    else if (isRecording) viewModel.stopRecording()
                    else viewModel.startRecording()
                },
                enabled = !isStopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStopping) Color.Gray
                    else if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (isStopping) "Stopping..." else if (isRecording) "STOP" else "START",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Session list
            if (sessions.isNotEmpty()) {
                Text(
                    "Past Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sessions) { session ->
                        SessionCard(
                            session,
                            onShare = { viewModel.shareSession(session.sessionId) },
                            onDelete = { viewModel.deleteSession(session.sessionId) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(isRecording: Boolean, durationMs: Long, totalBytes: Long, eventCount: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Recording indicator dot
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.Red else Color.Gray),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    if (isRecording) "Recording" else "Idle",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                if (isRecording) {
                    Text(formatDuration(durationMs), fontFamily = FontFamily.Monospace)
                    Text(formatBytes(totalBytes), fontFamily = FontFamily.Monospace)
                    if (eventCount > 0) {
                        Text("$eventCount event(s) marked", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventButtonsCard(
    onPothole: () -> Unit,
    onCrack: () -> Unit,
    onRough: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Mark Event (screen or BT remote)",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPothole,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Pothole", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onCrack,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Crack", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onRough,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Rough", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SensorRatesCard(accelHz: Float, gyroHz: Float, magHz: Float, gpsHz: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sensor Rates", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RateChip("Accel", accelHz)
                RateChip("Gyro", gyroHz)
                RateChip("Mag", magHz)
                RateChip("GPS", gpsHz)
            }
        }
    }
}

@Composable
private fun RateChip(label: String, hz: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (hz > 0) "%.0f Hz".format(hz) else "--",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SessionCard(session: SessionSummary, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.sessionId, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                if (session.routeOrigin.isNotBlank() || session.routeDestination.isNotBlank()) {
                    val route = buildString {
                        if (session.routeOrigin.isNotBlank()) append(session.routeOrigin)
                        if (session.routeOrigin.isNotBlank() && session.routeDestination.isNotBlank()) append(" \u2192 ")
                        if (session.routeDestination.isNotBlank()) append(session.routeDestination)
                    }
                    Text(route, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                val info = buildString {
                    append(formatBytes(session.sizeBytes))
                    if (session.eventCount > 0) append(" | ${session.eventCount} evt")
                    if (session.labelingMethod.isNotBlank()) append(" | ${session.labelingMethod}")
                }
                Text(info, fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = onShare) {
                Text("Share")
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
