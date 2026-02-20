package fr.nidsdepoule.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import fr.nidsdepoule.app.ui.MainScreen

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            viewModel.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            NidsDePouleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(
                        accelSamples = viewModel.accelSamples,
                        isMounted = viewModel.isMounted,
                        hasGpsFix = viewModel.hasGpsFix,
                        isConnected = viewModel.isConnected,
                        reportingMode = viewModel.reportingMode,
                        onModeChanged = { viewModel.setMode(it) },
                        hitsDetected = viewModel.hitsDetected,
                        hitsSent = viewModel.hitReporter.hitsSent,
                        hitsPending = viewModel.hitReporter.pendingCount,
                        kbLastMinute = viewModel.kbLastMinute,
                        mbLastHour = viewModel.mbLastHour,
                        mbThisMonth = viewModel.mbThisMonth,
                        appVersion = viewModel.appVersionName,
                        buildTime = BuildConfig.BUILD_TIME,
                        devModeEnabled = viewModel.devModeEnabled,
                        onDevModeTap = { viewModel.onDevModeTap() },
                        serverUrl = viewModel.serverUrl,
                        onServerUrlChanged = { viewModel.updateServerUrl(it) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            viewModel.start()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stop()
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
