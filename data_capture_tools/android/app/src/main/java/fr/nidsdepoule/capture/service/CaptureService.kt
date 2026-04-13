package fr.nidsdepoule.capture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import fr.nidsdepoule.capture.R
import fr.nidsdepoule.capture.location.LocationCollector
import fr.nidsdepoule.capture.location.RouteNamer
import fr.nidsdepoule.capture.recording.SessionRecorder
import fr.nidsdepoule.capture.sensor.SensorCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps sensor recording alive even when the screen is off.
 * Communicates state to CaptureViewModel via a bound service pattern.
 */
class CaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "sensor_capture"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context): Intent = Intent(context, CaptureService::class.java)
    }

    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private val binder = LocalBinder()
    private lateinit var sensorCollector: SensorCollector
    private lateinit var locationCollector: LocationCollector
    private lateinit var recorder: SessionRecorder

    // Rate measurement: count samples in rolling 1-second windows
    private var accelRateCount = 0L
    private var gyroRateCount = 0L
    private var magRateCount = 0L
    private var lastRateResetMs = 0L

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

    private var gpsRateCount = 0L
    private var firstGpsFix: Location? = null
    private var lastGpsFix: Location? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorCollector = SensorCollector(this)
        locationCollector = LocationCollector(this)
        recorder = SessionRecorder(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        return START_STICKY
    }

    fun startRecording() {
        if (_isRecording.value) return

        val sensorInfos = sensorCollector.getSensorInfos()
        recorder.start(sensorInfos)

        lastRateResetMs = System.currentTimeMillis()
        accelRateCount = 0; gyroRateCount = 0; magRateCount = 0; gpsRateCount = 0
        _eventCount.value = 0
        _totalBytes.value = 0; _durationMs.value = 0
        firstGpsFix = null; lastGpsFix = null

        sensorCollector.start(
            onAccel = { ts, x, y, z ->
                recorder.writeAccel(ts, x, y, z)
                accelRateCount++
                updateRates()
            },
            onGyro = { ts, x, y, z ->
                recorder.writeGyro(ts, x, y, z)
                gyroRateCount++
            },
            onMag = { ts, x, y, z ->
                recorder.writeMag(ts, x, y, z)
                magRateCount++
            },
        )

        locationCollector.start { location ->
            recorder.writeGps(location)
            gpsRateCount++
            if (firstGpsFix == null) firstGpsFix = location
            lastGpsFix = location
        }

        _isRecording.value = true
        updateNotification("Recording...")
    }

    fun stopRecording() {
        if (!_isRecording.value) return

        sensorCollector.stop()
        locationCollector.stop()
        recorder.stop()

        _isRecording.value = false
        _accelHz.value = 0f; _gyroHz.value = 0f; _magHz.value = 0f; _gpsHz.value = 0f
        updateNotification("Stopped")
    }

    fun recordEvent(eventType: String, source: String) {
        if (!_isRecording.value) return
        recorder.writeEvent(eventType, source)
        _eventCount.value++  // Increment immediately — don't read from recorder (async, stale)
    }

    fun annotateSession(
        routeOrigin: String,
        routeDestination: String,
        labelingMethod: String,
        labelingReliability: String,
        comment: String = "",
    ) {
        recorder.annotateLastSession(routeOrigin, routeDestination, labelingMethod, labelingReliability, comment)
    }

    fun getEventCountsByType(): Map<String, Long> = recorder.getEventCountsByType()

    /** Reverse-geocode first/last GPS fix to suggest origin/destination. Call from IO thread. */
    fun getRouteSuggestion(): RouteNamer.RouteSuggestion? =
        RouteNamer.suggest(this, firstGpsFix, lastGpsFix)

    fun getRecorder(): SessionRecorder = recorder

    private fun updateRates() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRateResetMs
        if (elapsed >= 1000) {
            val seconds = elapsed / 1000f
            _accelHz.value = accelRateCount / seconds
            _gyroHz.value = gyroRateCount / seconds
            _magHz.value = magRateCount / seconds
            _gpsHz.value = gpsRateCount / seconds
            accelRateCount = 0; gyroRateCount = 0; magRateCount = 0; gpsRateCount = 0
            lastRateResetMs = now

            _totalBytes.value = recorder.totalBytes
            _durationMs.value = recorder.durationMs
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sensor Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (_isRecording.value) stopRecording()
        recorder.release()
        super.onDestroy()
    }
}
