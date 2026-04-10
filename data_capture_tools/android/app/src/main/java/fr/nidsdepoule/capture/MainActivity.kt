package fr.nidsdepoule.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import fr.nidsdepoule.capture.ui.CaptureScreen

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: CaptureViewModel

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Start the service once permissions are resolved (granted or denied)
            viewModel.ensureServiceStarted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[CaptureViewModel::class.java]

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            // All permissions already granted — start service immediately
            viewModel.ensureServiceStarted()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen(viewModel)
                }
            }
        }
    }

    /**
     * Intercept ALL hardware key events before they reach Compose.
     * dispatchKeyEvent is the single entry point for all key events —
     * overriding only onKeyDown/onKeyUp is insufficient because some
     * events (especially from BT remotes) can bypass those methods.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only process the initial press, not repeats or releases
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            viewModel.onHardwareKey(event.keyCode)
        }
        // Consume ALL key events — never let them reach Compose's focus system
        return true
    }

}
