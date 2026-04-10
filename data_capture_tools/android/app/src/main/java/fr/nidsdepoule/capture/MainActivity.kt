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
     * Intercept hardware key events from Bluetooth remotes.
     * BT camera shutter remotes typically send VOLUME_UP or KEYCODE_CAMERA.
     * BT media remotes send MEDIA_PLAY_PAUSE, MEDIA_NEXT, etc.
     * Some remotes send ENTER/DPAD_CENTER — consume them too to prevent
     * Compose from clicking focused UI elements.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Ignore key repeat events (held down) — only handle initial press
        if (event != null && event.repeatCount > 0) return true

        viewModel.onHardwareKey(keyCode)
        // Always consume — prevent Compose from receiving any hardware keys
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Consume all key-up events too — Compose triggers button clicks on keyUp,
        // not keyDown, so we must intercept both.
        return true
    }

}
