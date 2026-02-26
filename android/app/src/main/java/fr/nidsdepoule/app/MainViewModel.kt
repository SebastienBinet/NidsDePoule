package fr.nidsdepoule.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import fr.nidsdepoule.app.detection.AccelRecorder
import fr.nidsdepoule.app.detection.HitEvent
import fr.nidsdepoule.app.detection.ReportSource
import fr.nidsdepoule.app.reporting.DataUsageTracker
import fr.nidsdepoule.app.reporting.HitReportData
import fr.nidsdepoule.app.reporting.HitReporter
import fr.nidsdepoule.app.reporting.OkHttpClientAdapter
import fr.nidsdepoule.app.sensor.AccelerometerCallback
import fr.nidsdepoule.app.sensor.AccelerometerSource
import fr.nidsdepoule.app.sensor.AndroidAccelerometer
import fr.nidsdepoule.app.sensor.AndroidLocationSource
import fr.nidsdepoule.app.sensor.CircuitLocationSource
import fr.nidsdepoule.app.sensor.LocationCallback
import fr.nidsdepoule.app.sensor.LocationReading
import fr.nidsdepoule.app.sensor.LocationSource
import fr.nidsdepoule.app.sensor.VoiceCommandListener
import fr.nidsdepoule.app.ui.AccelerationBuffer
import fr.nidsdepoule.app.ui.VoiceFeedback
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlin.math.sqrt

/**
 * ViewModel that connects all modules and exposes observable state to the UI.
 *
 * Two report types:
 *   Almost ("iiiiiiiii !!!") — pothole spotted nearby → geo only
 *   Hit ("AYOYE !?!#$!") — just drove over one → geo + 5s accel data
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- Configuration ---
    private val prefs = application.getSharedPreferences("nidsdepoule", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    val appVersion: Int = try {
        application.packageManager.getPackageInfo(application.packageName, 0).longVersionCode.toInt()
    } catch (_: Exception) { 1 }
    val appVersionName: String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.1.0"
    } catch (_: Exception) { "0.1.0" }

    // --- Core modules ---
    private val accelRecorder = AccelRecorder()
    val accelBuffer = AccelerationBuffer()
    private val dataUsageTracker = DataUsageTracker()
    private val voiceFeedback = VoiceFeedback(application)
    val voiceCommandListener = VoiceCommandListener(application)

    // --- Platform adapters ---
    private val accelerometer: AccelerometerSource = AndroidAccelerometer(application)
    private val realLocationSource: LocationSource = AndroidLocationSource(application)
    private val circuitLocationSource: LocationSource = CircuitLocationSource(application)
    private var activeLocationSource: LocationSource = realLocationSource

    // --- Server URL (persisted, read-only in UI) ---
    var serverUrl by mutableStateOf(
        prefs.getString("server_url", null) ?: BuildConfig.DEFAULT_SERVER_URL
    )
        private set

    // --- Reporting ---
    private val httpClient = OkHttpClientAdapter()
    val hitReporter = HitReporter(
        httpClient = httpClient,
        dataUsageTracker = dataUsageTracker,
        deviceId = deviceId,
        appVersion = appVersion,
        serverUrl = serverUrl,
    )

    // --- Location tracking for bearing before/after ---
    private val locationHistory = ArrayDeque<LocationReading>(100)
    private var lastLocation: LocationReading? = null

    // --- Observable UI state ---
    var hasGpsFix by mutableStateOf(false)
        private set
    var isConnected by mutableStateOf(false)
        private set
    var hitsDetected by mutableIntStateOf(0)
        private set
    var reportingMode by mutableStateOf(HitReporter.Mode.REALTIME)
        private set
    var devModeEnabled by mutableStateOf(BuildConfig.DEV_MODE_DEFAULT)
        private set
    var accelSamples by mutableStateOf<List<AccelerationBuffer.Sample>>(emptyList())
        private set
    var hitFlashActive by mutableStateOf(false)
        private set
    var hitFlashText by mutableStateOf("HIT!")
        private set
    var isSimulating by mutableStateOf(false)
        private set
    var voiceMuted by mutableStateOf(false)
        private set

    // Dev mode tap counter
    private var devModeTapCount = 0
    private var lastDevModeTapMs = 0L

    // Handler for UI-thread delayed tasks
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Lifecycle ---
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        // Wire connectivity callback
        hitReporter.onConnectivityChanged = { connected ->
            isConnected = connected
        }
        hitReporter.checkConnectivity()

        // Restore month data
        val savedMonthBytes = prefs.getLong("month_bytes", 0)
        val savedMonthStart = prefs.getLong("month_start_ms", 0)
        dataUsageTracker.restoreMonth(savedMonthBytes, savedMonthStart)

        // Start accelerometer (TYPE_LINEAR_ACCELERATION — gravity already removed)
        accelerometer.start(AccelerometerCallback { timestamp, x, y, z ->
            val magnitudeMg = sqrt((x.toLong() * x + y.toLong() * y + z.toLong() * z).toFloat()).toInt()

            // Buffer for graph display
            accelBuffer.add(timestamp, magnitudeMg)

            // Buffer for Hit waveform capture
            accelRecorder.addReading(timestamp, magnitudeMg)

            // Update graph samples periodically (every ~200ms = every 10th reading at 50Hz)
            if (accelBuffer.size % 10 == 0) {
                accelSamples = accelBuffer.snapshot(step = 4)
            }
        })

        // Start GPS (real or simulated)
        startLocationSource()

        // Wire voice command listener
        voiceCommandListener.onAlmost = { onReportAlmost() }
        voiceCommandListener.onHit = { onReportHit() }
    }

    /** Shared location callback used by both real and simulated GPS. */
    private val locationCb = LocationCallback { reading ->
        hasGpsFix = true
        lastLocation = reading

        synchronized(locationHistory) {
            locationHistory.addLast(reading)
            if (locationHistory.size > 100) {
                locationHistory.removeFirst()
            }
        }
    }

    private fun startLocationSource() {
        activeLocationSource.start(locationCb)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        accelerometer.stop()
        activeLocationSource.stop()
        voiceFeedback.shutdown()
        voiceCommandListener.stop()

        // Persist month data
        prefs.edit()
            .putLong("month_bytes", dataUsageTracker.monthBytes)
            .putLong("month_start_ms", dataUsageTracker.monthStartMs)
            .apply()
    }

    fun setMode(mode: HitReporter.Mode) {
        reportingMode = mode
        hitReporter.mode = mode
    }

    fun onDevModeTap() {
        val now = System.currentTimeMillis()
        if (now - lastDevModeTapMs > 2000) {
            devModeTapCount = 0
        }
        devModeTapCount++
        lastDevModeTapMs = now

        if (devModeTapCount >= 7) {
            devModeEnabled = !devModeEnabled
            devModeTapCount = 0
        }
    }

    fun toggleVoice() {
        voiceMuted = !voiceMuted
    }

    /** Toggle between real GPS and cemetery circuit simulation. */
    fun toggleSimulation() {
        if (!isRunning) return
        activeLocationSource.stop()
        isSimulating = !isSimulating
        activeLocationSource = if (isSimulating) circuitLocationSource else realLocationSource
        hasGpsFix = false
        lastLocation = null
        synchronized(locationHistory) { locationHistory.clear() }
        startLocationSource()
    }

    // --- Report buttons ---

    /** "iiiiiiiii !!!" — there's a pothole near me (Almost). Geo only, no accel data. */
    fun onReportAlmost() {
        val location = lastLocation ?: return
        val event = HitEvent(
            timestampMs = System.currentTimeMillis(),
            peakVerticalMg = 0,
            peakLateralMg = 0,
            durationMs = 0,
            severity = 2,
            waveformVertical = emptyList(),
            waveformLateral = emptyList(),
            baselineMg = 0,
            peakToBaselineRatio = 0,
            source = ReportSource.ALMOST,
        )
        hitsDetected++
        sendReport(event, location, "iiiiiiiii !!!")
    }

    /** "AYOYE !?!#$!" — I just hit a pothole (Hit). Captures last 5s of accel data. */
    fun onReportHit() {
        val location = lastLocation ?: return
        val event = buildHitEvent()
        hitsDetected++
        accelBuffer.markLastAsHit()
        sendReport(event, location, "AYOYE !?!#\$!")
    }

    /** Build a HitEvent from the last 5 seconds of accelerometer data. */
    private fun buildHitEvent(): HitEvent {
        val readings = accelRecorder.recentReadings(5000)
        if (readings.isEmpty()) {
            return HitEvent(
                timestampMs = System.currentTimeMillis(),
                peakVerticalMg = 0, peakLateralMg = 0, durationMs = 0,
                severity = 3,
                waveformVertical = emptyList(), waveformLateral = emptyList(),
                baselineMg = 0, peakToBaselineRatio = 0,
                source = ReportSource.HIT,
            )
        }

        // Find peak magnitude in the captured window.
        var peakIdx = 0
        var peakMag = 0
        for ((i, r) in readings.withIndex()) {
            if (r.magnitudeMg > peakMag) { peakMag = r.magnitudeMg; peakIdx = i }
        }

        // Extract waveform (up to 150 samples centered on peak).
        val half = 75
        val start = maxOf(0, peakIdx - half)
        val end = minOf(readings.size, peakIdx + half)
        val waveform = readings.subList(start, end).map { it.magnitudeMg }

        // Baseline = median magnitude.
        val sorted = readings.map { it.magnitudeMg }.sorted()
        val baseline = sorted[sorted.size / 2]
        val ratio = if (baseline > 0) (peakMag.toDouble() / baseline * 100).toInt() else 0
        val duration = (readings.last().timestamp - readings.first().timestamp).toInt()

        return HitEvent(
            timestampMs = readings[peakIdx].timestamp,
            peakVerticalMg = peakMag,
            peakLateralMg = 0,
            durationMs = duration,
            severity = 3,
            waveformVertical = waveform,
            waveformLateral = emptyList(),
            baselineMg = baseline,
            peakToBaselineRatio = ratio,
            source = ReportSource.HIT,
        )
    }

    private fun sendReport(event: HitEvent, location: LocationReading, flashText: String = "HIT!") {
        triggerHitFlash(flashText)
        if (!voiceMuted) {
            voiceFeedback.speakHit(event.severity)
        }
        val bearingBefore = computeBearingBefore()
        val bearingAfter = location.bearingDeg
        val report = HitReportData.create(event, location, bearingBefore, bearingAfter)
        hitReporter.report(report)
    }

    // --- Data usage accessors (for UI) ---
    val kbLastMinute: Float get() = dataUsageTracker.kbLastMinute()
    val mbLastHour: Float get() = dataUsageTracker.mbLastHour()
    val mbThisMonth: Float get() = dataUsageTracker.mbThisMonth()

    // --- Private ---

    private fun triggerHitFlash(text: String = "HIT!") {
        hitFlashText = text
        hitFlashActive = true
        mainHandler.postDelayed({ hitFlashActive = false }, 600)
    }

    private fun computeBearingBefore(): Float {
        val current = lastLocation ?: return 0f
        synchronized(locationHistory) {
            for (i in locationHistory.size - 2 downTo 0) {
                val prev = locationHistory[i]
                val distM = haversineDistance(
                    prev.latMicrodeg / 1_000_000.0, prev.lonMicrodeg / 1_000_000.0,
                    current.latMicrodeg / 1_000_000.0, current.lonMicrodeg / 1_000_000.0,
                )
                if (distM >= 20.0) {
                    return bearing(
                        prev.latMicrodeg / 1_000_000.0, prev.lonMicrodeg / 1_000_000.0,
                        current.latMicrodeg / 1_000_000.0, current.lonMicrodeg / 1_000_000.0,
                    )
                }
            }
        }
        return current.bearingDeg
    }

    companion object {
        fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return R * c
        }

        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val dLon = Math.toRadians(lon2 - lon1)
            val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
            val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
                    Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
            return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
        }
    }
}
