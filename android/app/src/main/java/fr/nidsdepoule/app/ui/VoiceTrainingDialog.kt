package fr.nidsdepoule.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.nidsdepoule.app.sensor.audio.AudioCapture
import fr.nidsdepoule.app.sensor.audio.MfccExtractor
import fr.nidsdepoule.app.sensor.audio.VoiceProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for recording voice training samples.
 *
 * Cycles through each keyword in the group (Almost or Hit) and records
 * 6 samples per keyword. The user presses a button to start each recording.
 */
@Composable
fun VoiceTrainingDialog(
    keywords: List<String>,
    groupLabel: String,
    profileStore: VoiceProfileStore,
    mfccExtractor: MfccExtractor,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    var currentKeywordIndex by remember { mutableIntStateOf(0) }
    var currentSampleIndex by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStatus by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val totalKeywords = keywords.size
    val samplesPerKeyword = VoiceProfileStore.SAMPLES_PER_KEYWORD
    val currentKeyword = if (currentKeywordIndex < totalKeywords) keywords[currentKeywordIndex] else ""
    val overallProgress = currentKeywordIndex * samplesPerKeyword + currentSampleIndex
    val totalSamples = totalKeywords * samplesPerKeyword

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Title
                Text(
                    text = "Voice Training — $groupLabel",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Overall progress
                Text(
                    text = "$overallProgress / $totalSamples samples",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                LinearProgressIndicator(
                    progress = { overallProgress.toFloat() / totalSamples },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Current keyword
                Text(
                    text = "Say:",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )

                Text(
                    text = "\"$currentKeyword\"",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Sample dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (i in 0 until samplesPerKeyword) {
                        val dotColor = when {
                            i < currentSampleIndex -> Color(0xFF4CAF50) // completed
                            i == currentSampleIndex && isRecording -> Color(0xFFFF1744) // recording
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val animatedColor by animateColorAsState(
                            targetValue = dotColor,
                            label = "dot$i",
                        )
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(12.dp)
                                .background(animatedColor, CircleShape),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status text
                if (recordingStatus.isNotEmpty()) {
                    Text(
                        text = recordingStatus,
                        fontSize = 12.sp,
                        color = if (isRecording) Color(0xFFFF1744)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Record button
                Button(
                    onClick = {
                        if (!isRecording && !isProcessing && currentKeywordIndex < totalKeywords) {
                            scope.launch {
                                recordSample(
                                    keyword = currentKeyword,
                                    sampleIndex = currentSampleIndex,
                                    profileStore = profileStore,
                                    mfccExtractor = mfccExtractor,
                                    onRecordingStart = {
                                        isRecording = true
                                        recordingStatus = "Recording... speak now!"
                                    },
                                    onRecordingEnd = {
                                        isRecording = false
                                        isProcessing = true
                                        recordingStatus = "Processing..."
                                    },
                                    onSaved = {
                                        isProcessing = false
                                        recordingStatus = "Saved!"
                                        currentSampleIndex++
                                        if (currentSampleIndex >= samplesPerKeyword) {
                                            currentSampleIndex = 0
                                            currentKeywordIndex++
                                            if (currentKeywordIndex >= totalKeywords) {
                                                recordingStatus = "Training complete!"
                                            }
                                        }
                                    },
                                    onError = { msg ->
                                        isRecording = false
                                        isProcessing = false
                                        recordingStatus = "Error: $msg"
                                    },
                                )
                            }
                        }
                    },
                    enabled = !isRecording && !isProcessing && currentKeywordIndex < totalKeywords,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFFF1744) else MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = when {
                            isRecording -> "Recording..."
                            isProcessing -> "Processing..."
                            currentKeywordIndex >= totalKeywords -> "Done!"
                            else -> "Record (${currentSampleIndex + 1}/$samplesPerKeyword)"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Close / Done button
                TextButton(
                    onClick = {
                        if (currentKeywordIndex >= totalKeywords) {
                            onComplete()
                        } else {
                            onDismiss()
                        }
                    },
                ) {
                    Text(
                        text = if (currentKeywordIndex >= totalKeywords) "Done" else "Cancel",
                    )
                }
            }
        }
    }
}

private suspend fun recordSample(
    keyword: String,
    sampleIndex: Int,
    profileStore: VoiceProfileStore,
    mfccExtractor: MfccExtractor,
    onRecordingStart: () -> Unit,
    onRecordingEnd: () -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit,
) {
    withContext(Dispatchers.Main) { onRecordingStart() }

    val pcm = withContext(Dispatchers.IO) {
        val capture = AudioCapture()
        capture.recordSegment(2000) // 2 seconds
    }

    withContext(Dispatchers.Main) { onRecordingEnd() }

    if (pcm == null || pcm.isEmpty()) {
        withContext(Dispatchers.Main) { onError("Failed to record audio") }
        return
    }

    // Extract MFCC features
    val mfcc = withContext(Dispatchers.Default) {
        mfccExtractor.extract(pcm)
    }

    if (mfcc.isEmpty()) {
        withContext(Dispatchers.Main) { onError("No audio features detected") }
        return
    }

    // Save the template
    withContext(Dispatchers.IO) {
        profileStore.saveTemplate(keyword, sampleIndex, mfcc)
    }

    // Brief pause so user sees "Saved!"
    delay(400)

    withContext(Dispatchers.Main) { onSaved() }
}
