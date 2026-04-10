package fr.nidsdepoule.capture

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nidsdepoule.capture.recording.SessionSummary
import fr.nidsdepoule.capture.service.CaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private var service: CaptureService? = null
    private var bound = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _accelHz = MutableStateFlow(0f)
    val accelHz: StateFlow<Float> = _accelHz
    private val _gyroHz = MutableStateFlow(0f)
    val gyroHz: StateFlow<Float> = _gyroHz
    private val _magHz = MutableStateFlow(0f)
    val magHz: StateFlow<Float> = _magHz
    private val _gpsHz = MutableStateFlow(0f)
    val gpsHz: StateFlow<Float> = _gpsHz

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _eventCount = MutableStateFlow(0L)
    val eventCount: StateFlow<Long> = _eventCount

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions

    private val _isStopping = MutableStateFlow(false)
    val isStopping: StateFlow<Boolean> = _isStopping

    // Post-capture dialog state
    private val _showStopDialog = MutableStateFlow(false)
    val showStopDialog: StateFlow<Boolean> = _showStopDialog
    private val _lastSessionId = MutableStateFlow("")
    val lastSessionId: StateFlow<String> = _lastSessionId
    private val _lastDurationMs = MutableStateFlow(0L)
    val lastDurationMs: StateFlow<Long> = _lastDurationMs
    private val _lastEventCounts = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastEventCounts: StateFlow<Map<String, Long>> = _lastEventCounts
    private val _suggestedOrigin = MutableStateFlow("")
    val suggestedOrigin: StateFlow<String> = _suggestedOrigin
    private val _suggestedDestination = MutableStateFlow("")
    val suggestedDestination: StateFlow<String> = _suggestedDestination

    // Share intent for launching Android share sheet
    private val _shareIntent = MutableStateFlow<Intent?>(null)
    val shareIntent: StateFlow<Intent?> = _shareIntent

    // Last hardware key debug info (counter ensures MutableStateFlow always emits)
    private var keyPressCount = 0
    private val _lastKeyInfo = MutableStateFlow("")
    val lastKeyInfo: StateFlow<String> = _lastKeyInfo

    // Event flash: incremented each time an event is captured, UI observes to flash
    private val _eventFlash = MutableStateFlow(0)
    val eventFlash: StateFlow<Int> = _eventFlash

    // Which button to highlight on BT press (e.g. "pothole", "crack")
    private val _highlightButton = MutableStateFlow("")
    val highlightButton: StateFlow<String> = _highlightButton

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as CaptureService.LocalBinder).service
            bound = true
            startCollectingState()
            refreshSessions()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private var serviceStarted = false

    init {
        refreshSessions()
    }

    /** Called by MainActivity after permissions are resolved. Starts the foreground service. */
    fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        val ctx = getApplication<Application>()
        val intent = CaptureService.startIntent(ctx)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun startCollectingState() {
        viewModelScope.launch {
            val svc = service ?: return@launch
            // Poll service state flows every 500ms for UI updates
            while (true) {
                _isRecording.value = svc.isRecording.value
                _accelHz.value = svc.accelHz.value
                _gyroHz.value = svc.gyroHz.value
                _magHz.value = svc.magHz.value
                _gpsHz.value = svc.gpsHz.value
                _totalBytes.value = svc.totalBytes.value
                _durationMs.value = svc.durationMs.value
                _eventCount.value = svc.eventCount.value
                delay(500)
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { service?.startRecording() }
        }
    }

    fun stopRecording() {
        val svc = service ?: return
        _isStopping.value = true
        _lastDurationMs.value = svc.durationMs.value

        // All blocking work (file I/O, geocoding, thread joins) off the main thread
        viewModelScope.launch {
            val eventCounts = withContext(Dispatchers.IO) { svc.getEventCountsByType() }
            _lastEventCounts.value = eventCounts

            // Reverse-geocode before stopping (GPS still has fixes)
            val suggestion = withContext(Dispatchers.IO) { svc.getRouteSuggestion() }
            _suggestedOrigin.value = suggestion?.origin ?: ""
            _suggestedDestination.value = suggestion?.destination ?: ""

            withContext(Dispatchers.IO) { svc.stopRecording() }

            refreshSessions()
            val latest = _sessions.value.firstOrNull()
            if (latest != null) {
                _lastSessionId.value = latest.sessionId
            }
            _isStopping.value = false
            _showStopDialog.value = true
        }
    }

    fun annotateSession(
        routeOrigin: String,
        routeDestination: String,
        labelingMethod: String,
        labelingReliability: String,
        comment: String = "",
    ) {
        _showStopDialog.value = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                service?.annotateSession(routeOrigin, routeDestination, labelingMethod, labelingReliability, comment)
            }
            refreshSessions()
        }
    }

    fun dismissStopDialog() {
        _showStopDialog.value = false
    }

    fun recordEvent(eventType: String, source: String) {
        service?.recordEvent(eventType, source)
        if (source == "screen_button") {
            _eventFlash.value++
        }
    }

    /**
     * Called from MainActivity.onKeyDown() when a BT remote key is pressed.
     * Maps volume keys to event types. Always shows key info for debugging.
     */
    fun onHardwareKey(keyCode: Int): Boolean {
        val keyName = android.view.KeyEvent.keyCodeToString(keyCode)
        keyPressCount++
        val eventType = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "pothole"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "other"
            android.view.KeyEvent.KEYCODE_CAMERA -> "pothole"
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "pothole"
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "other"
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "other"
            android.view.KeyEvent.KEYCODE_ENTER -> "crack"
            android.view.KeyEvent.KEYCODE_DPAD_CENTER -> "crack"
            else -> null
        }

        if (eventType != null) {
            _lastKeyInfo.value = "#$keyPressCount $keyName ($keyCode) → $eventType"
            // Always try to record — the service ignores if not recording.
            // Don't check ViewModel's _isRecording (polled, can be stale by 500ms).
            recordEvent(eventType, "bt_button")
            vibrate()
            _eventFlash.value = keyPressCount
            _highlightButton.value = "$eventType:$keyPressCount"  // counter forces re-emit
            return true
        } else {
            _lastKeyInfo.value = "#$keyPressCount $keyName ($keyCode) → not mapped"
            return false
        }
    }

    private fun vibrate() {
        val ctx = getApplication<Application>()
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun shareSession(sessionId: String) {
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) {
                val recorder = service?.getRecorder() ?: return@withContext null
                val sessionDir = recorder.findSessionDir(sessionId) ?: return@withContext null

                // Zip session directory to cache
                val ctx = getApplication<Application>()
                val shareDir = File(ctx.cacheDir, "shared_sessions").apply { mkdirs() }
                val zipFile = File(shareDir, "${sessionDir.name}.zip")
                zipDirectory(sessionDir, zipFile)

                // Copy session directory name to clipboard (for pasting into the Google Sheet index)
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("session_name", sessionDir.name))

                // Create share intent via FileProvider
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zipFile)
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, sessionDir.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            if (intent != null) {
                _shareIntent.value = intent
            }
        }
    }

    fun clearShareIntent() {
        _shareIntent.value = null
    }

    private fun zipDirectory(dir: File, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = "${dir.name}/${file.relativeTo(dir).path}"
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                service?.getRecorder()?.deleteSession(sessionId)
            }
            refreshSessions()
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            val recorder = service?.getRecorder() ?: return@launch
            val list = withContext(Dispatchers.IO) { recorder.listSessions() }
            _sessions.value = list
        }
    }

    override fun onCleared() {
        if (bound) {
            getApplication<Application>().unbindService(connection)
            bound = false
        }
        super.onCleared()
    }
}
