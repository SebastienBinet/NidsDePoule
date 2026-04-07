package fr.nidsdepoule.capture

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nidsdepoule.capture.recording.SessionSummary
import fr.nidsdepoule.capture.service.CaptureService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    // Post-capture dialog state
    private val _showStopDialog = MutableStateFlow(false)
    val showStopDialog: StateFlow<Boolean> = _showStopDialog
    private val _lastSessionId = MutableStateFlow("")
    val lastSessionId: StateFlow<String> = _lastSessionId
    private val _lastDurationMs = MutableStateFlow(0L)
    val lastDurationMs: StateFlow<Long> = _lastDurationMs
    private val _lastEventCounts = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastEventCounts: StateFlow<Map<String, Long>> = _lastEventCounts

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

    init {
        val ctx = getApplication<Application>()
        val intent = CaptureService.startIntent(ctx)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        refreshSessions()
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
        service?.startRecording()
    }

    fun stopRecording() {
        val svc = service ?: return
        // Capture info before stopping
        _lastDurationMs.value = svc.durationMs.value
        _lastEventCounts.value = svc.getEventCountsByType()
        _lastSessionId.value = svc.getRecorder().let {
            // sessionId is set during start(); grab it via the recorder
            _lastDurationMs.value // trigger read
            svc.durationMs.value
            "" // will be set below
        }

        svc.stopRecording()

        // Get the session ID from the most recent session
        viewModelScope.launch {
            delay(500) // Wait for I/O thread to finish
            refreshSessions()
            val latest = _sessions.value.firstOrNull()
            if (latest != null) {
                _lastSessionId.value = latest.sessionId
            }
            _showStopDialog.value = true
        }
    }

    fun annotateSession(
        routeOrigin: String,
        routeDestination: String,
        labelingMethod: String,
        labelingReliability: String,
    ) {
        service?.annotateSession(routeOrigin, routeDestination, labelingMethod, labelingReliability)
        _showStopDialog.value = false
        refreshSessions()
    }

    fun dismissStopDialog() {
        _showStopDialog.value = false
    }

    fun recordEvent(eventType: String, source: String) {
        service?.recordEvent(eventType, source)
    }

    /**
     * Called from MainActivity.onKeyDown() when a BT remote key is pressed.
     * Maps volume keys to event types.
     */
    fun onHardwareKey(keyCode: Int): Boolean {
        if (!_isRecording.value) return false
        val eventType = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "pothole"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "other"
            android.view.KeyEvent.KEYCODE_CAMERA -> "pothole"
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "pothole"
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "other"
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "other"
            else -> return false
        }
        recordEvent(eventType, "bt_button")
        return true
    }

    fun deleteSession(sessionId: String) {
        service?.getRecorder()?.deleteSession(sessionId)
        refreshSessions()
    }

    fun refreshSessions() {
        viewModelScope.launch {
            val recorder = service?.getRecorder()
            if (recorder != null) {
                _sessions.value = recorder.listSessions()
            }
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
