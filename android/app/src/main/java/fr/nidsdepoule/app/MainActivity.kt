package fr.nidsdepoule.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import fr.nidsdepoule.app.sensor.VoiceCommandListener
import fr.nidsdepoule.app.ui.MainScreen

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            window.decorView.post { startDetection() }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.voiceCommandListener.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Keep screen on while detection is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            NidsDePouleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(
                        accelSamples = viewModel.accelSamples,
                        hasGpsFix = viewModel.hasGpsFix,
                        isConnected = viewModel.isConnected,
                        hitsDetected = viewModel.hitsDetected,
                        hitsSent = viewModel.hitReporter.hitsSent,
                        hitsPending = viewModel.hitReporter.pendingCount,
                        kbLastMinute = viewModel.kbLastMinute,
                        mbLastHour = viewModel.mbLastHour,
                        mbThisMonth = viewModel.mbThisMonth,
                        appVersion = viewModel.appVersionName,
                        buildTime = BuildConfig.BUILD_TIME,
                        devModeEnabled = viewModel.devModeEnabled,
                        onAlmost = { viewModel.onReportAlmost() },
                        onHit = { viewModel.onReportHit() },
                        hitFlashActive = viewModel.hitFlashActive,
                        hitFlashText = viewModel.hitFlashText,
                        isSimulating = viewModel.isSimulating,
                        onToggleSimulation = { viewModel.toggleSimulation() },
                        onDevModeTap = { viewModel.onDevModeTap() },
                        serverUrl = viewModel.serverUrl,
                        voiceMuted = viewModel.voiceMuted,
                        onToggleVoice = { viewModel.toggleVoice() },
                        isListening = viewModel.voiceCommandListener.isListening,
                        // Map
                        locationHistory = viewModel.locationHistorySnapshot,
                        mapMarkers = viewModel.mapMarkers,
                        // Voice training
                        showVoiceTraining = viewModel.showVoiceTraining,
                        voiceTrainingKeywords = viewModel.voiceTrainingKeywords,
                        voiceTrainingLabel = viewModel.voiceTrainingLabel,
                        onAlmostLongPress = {
                            viewModel.startVoiceTraining(
                                VoiceCommandListener.ALMOST_KEYWORDS, "Almost"
                            )
                        },
                        onHitLongPress = {
                            viewModel.startVoiceTraining(
                                VoiceCommandListener.HIT_KEYWORDS, "Hit"
                            )
                        },
                        onVoiceTrainingDismiss = { viewModel.onVoiceTrainingDismiss() },
                        onVoiceTrainingComplete = { viewModel.onVoiceTrainingComplete() },
                        profileStore = viewModel.voiceCommandListener.getProfileStore(),
                        mfccExtractor = viewModel.voiceCommandListener.getMfccExtractor(),
                        // Voice match overlay
                        voiceMatchScores = viewModel.voiceCommandListener.matchScores,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            // Defer to let Compose render the first frame before heavy init
            window.decorView.post { startDetection() }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, DetectionService::class.java))
        viewModel.stop()
    }

    private fun startDetection() {
        viewModel.start()
        val serviceIntent = Intent(this, DetectionService::class.java)
        startForegroundService(serviceIntent)
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        // Request microphone permission for voice commands
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            viewModel.voiceCommandListener.start()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun NidsDePouleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
