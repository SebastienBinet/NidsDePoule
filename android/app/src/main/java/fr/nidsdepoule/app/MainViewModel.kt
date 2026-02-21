package fr.nidsdepoule.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import fr.nidsdepoule.app.detection.AccelReading
import fr.nidsdepoule.app.detection.CarMountDetector
import fr.nidsdepoule.app.detection.HitDetectionStrategy
import fr.nidsdepoule.app.detection.HitEvent
import fr.nidsdepoule.app.detection.ReportSource
import fr.nidsdepoule.app.detection.ThresholdHitDetector
import kotlin.math.abs
import fr.nidsdepoule.app.reporting.DataUsageTracker
import fr.nidsdepoule.app.reporting.HitReportData
import fr.nidsdepoule.app.reporting.HitReporter
import fr.nidsdepoule.app.reporting.OkHttpClientAdapter
import fr.nidsdepoule.app.sensor.AccelerometerCallback
import fr.nidsdepoule.app.sensor.AccelerometerSource
import fr.nidsdepoule.app.sensor.AndroidAccelerometer
import fr.nidsdepoule.app.sensor.AndroidLocationSource
import fr.nidsdepoule.app.sensor.LocationCallback
import fr.nidsdepoule.app.sensor.LocationReading
import fr.nidsdepoule.app.sensor.LocationSource
import fr.nidsdepoule.app.ui.AccelerationBuffer
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * ViewModel that connects all modules and exposes observable state to the UI.
 *
 * Module wiring:
 *   Accelerometer → HitDetector + CarMountDetector + AccelBuffer
 *   GPS → LocationTracker → (bearing before/after computation)
 *   HitDetector + LocationTracker → HitReporter → Server
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

    // --- Core modules (pure Kotlin, no Android deps) ---
    private val hitDetector: HitDetectionStrategy = ThresholdHitDetector()
    private val carMountDetector = CarMountDetector()
    val accelBuffer = AccelerationBuffer()
    private val dataUsageTracker = DataUsageTracker()

    // --- Platform adapters ---
    private val accelerometer: AccelerometerSource = AndroidAccelerometer(application)
    private val locationSource: LocationSource = AndroidLocationSource(application)

    // --- Server URL (persisted, editable in dev mode) ---
    var serverUrl by mutableStateOf(
        prefs.getString("server_url", null) ?: BuildConfig.DEFAULT_SERVER_URL
    )
        private set

    fun updateServerUrl(url: String) {
        serverUrl = url
        hitReporter.serverUrl = url
        prefs.edit().putString("server_url", url).apply()
    }

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
    var isMounted by mutableStateOf(false)
        private set
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
    /** True for a short moment after a hit is detected — drives visual flash. */
    var hitFlashActive by mutableStateOf(false)
        private set

    // Dev mode tap counter
    private var devModeTapCount = 0
    private var lastDevModeTapMs = 0L

    // Handler for UI-thread delayed tasks (e.g., clearing hit flash)
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
        // Initial server health check
        hitReporter.checkConnectivity()

        // Restore month data
        val savedMonthBytes = prefs.getLong("month_bytes", 0)
        val savedMonthStart = prefs.getLong("month_start_ms", 0)
        dataUsageTracker.restoreMonth(savedMonthBytes, savedMonthStart)

        // Start accelerometer
        accelerometer.start(AccelerometerCallback { timestamp, x, _, z ->
            // z = vertical (up-down), x = lateral (left-right)
            val verticalMg = z
            val lateralMg = x

            // Feed all consumers
            accelBuffer.add(timestamp, verticalMg, lateralMg)
            carMountDetector.processReading(x, 0, z)
            isMounted = carMountDetector.isMounted

            // Only detect hits when mounted (or in dev mode)
            if (isMounted || devModeEnabled) {
                val speed = lastLocation?.speedMps ?: 0f
                val event = hitDetector.processReading(timestamp, verticalMg, lateralMg, speed)

                if (event != null) {
                    hitsDetected++
                    accelBuffer.markLastAsHit()
                    onHitDetected(event)
                }
            }

            // Update graph samples periodically (every ~200ms = every 10th reading at 50Hz)
            if (accelBuffer.size % 10 == 0) {
                accelSamples = accelBuffer.snapshot(step = 4)
            }
        })

        // Start GPS
        locationSource.start(LocationCallback { reading ->
            hasGpsFix = true
            lastLocation = reading
            carMountDetector.updateSpeed(reading.speedMps)

            // Keep location history for bearing before/after computation
            synchronized(locationHistory) {
                locationHistory.addLast(reading)
                if (locationHistory.size > 100) {
                    locationHistory.removeFirst()
                }
            }
        })
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        accelerometer.stop()
        locationSource.stop()

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

    // --- Manual report buttons ---

    /** "Hiii !" — user visually spots a small/medium pothole. */
    fun onReportVisualSmall() {
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
            source = ReportSource.VISUAL_SMALL,
        )
        hitsDetected++
        sendReport(event, location)
    }

    /** "HIIIIIII !!!" — user visually spots a big pothole. */
    fun onReportVisualBig() {
        val location = lastLocation ?: return
        val event = HitEvent(
            timestampMs = System.currentTimeMillis(),
            peakVerticalMg = 0,
            peakLateralMg = 0,
            durationMs = 0,
            severity = 3,
            waveformVertical = emptyList(),
            waveformLateral = emptyList(),
            baselineMg = 0,
            peakToBaselineRatio = 0,
            source = ReportSource.VISUAL_BIG,
        )
        hitsDetected++
        sendReport(event, location)
    }

    /** "Ouch !" — user just hit a small/medium pothole; capture last 5s of accel data. */
    fun onReportImpactSmall() {
        val location = lastLocation ?: return
        val event = buildImpactEvent(ReportSource.IMPACT_SMALL, userSeverity = 2)
        hitsDetected++
        accelBuffer.markLastAsHit()
        sendReport(event, location)
    }

    /** "AYOYE !" — user just hit a big pothole; capture last 5s of accel data. */
    fun onReportImpactBig() {
        val location = lastLocation ?: return
        val event = buildImpactEvent(ReportSource.IMPACT_BIG, userSeverity = 3)
        hitsDetected++
        accelBuffer.markLastAsHit()
        sendReport(event, location)
    }

    /** Build a HitEvent from the last 5 seconds of accelerometer data. */
    private fun buildImpactEvent(source: ReportSource, userSeverity: Int): HitEvent {
        val readings = hitDetector.recentReadings(5000)
        if (readings.isEmpty()) {
            return HitEvent(
                timestampMs = System.currentTimeMillis(),
                peakVerticalMg = 0, peakLateralMg = 0, durationMs = 0,
                severity = userSeverity,
                waveformVertical = emptyList(), waveformLateral = emptyList(),
                baselineMg = 0, peakToBaselineRatio = 0,
                source = source,
            )
        }

        // Find peak in the captured window.
        var peakIdx = 0
        var peakV = 0
        for ((i, r) in readings.withIndex()) {
            val av = abs(r.verticalMg)
            if (av > peakV) { peakV = av; peakIdx = i }
        }
        val peakL = abs(readings[peakIdx].lateralMg)

        // Extract waveform (up to 150 samples centered on peak).
        val half = 75
        val start = maxOf(0, peakIdx - half)
        val end = minOf(readings.size, peakIdx + half)
        val wV = readings.subList(start, end).map { it.verticalMg }
        val wL = readings.subList(start, end).map { it.lateralMg }

        // Baseline = median absolute vertical.
        val sorted = readings.map { abs(it.verticalMg) }.sorted()
        val baseline = sorted[sorted.size / 2]
        val ratio = if (baseline > 0) (peakV.toDouble() / baseline * 100).toInt() else 0
        val duration = (readings.last().timestamp - readings.first().timestamp).toInt()

        return HitEvent(
            timestampMs = readings[peakIdx].timestamp,
            peakVerticalMg = peakV,
            peakLateralMg = peakL,
            durationMs = duration,
            severity = userSeverity,
            waveformVertical = wV,
            waveformLateral = wL,
            baselineMg = baseline,
            peakToBaselineRatio = ratio,
            source = source,
        )
    }

    private fun sendReport(event: HitEvent, location: LocationReading) {
        triggerHitFlash()
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

    private fun triggerHitFlash() {
        hitFlashActive = true
        mainHandler.postDelayed({ hitFlashActive = false }, 600)
    }

    private fun onHitDetected(event: HitEvent) {
        val location = lastLocation ?: return  // No GPS → can't report
        triggerHitFlash()

        val bearingBefore = computeBearingBefore()
        val bearingAfter = location.bearingDeg  // Use current bearing as estimate for "after"

        val report = HitReportData.create(event, location, bearingBefore, bearingAfter)
        hitReporter.report(report)
    }

    /**
     * Compute the bearing from ~20m before the hit to the hit position.
     * Uses the location history to find the position approximately 20m back.
     */
    private fun computeBearingBefore(): Float {
        val current = lastLocation ?: return 0f
        synchronized(locationHistory) {
            // Walk backwards through location history to find a point ~20m back
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
        return current.bearingDeg  // Fallback to current GPS bearing
    }

    companion object {
        /** Haversine distance in meters between two lat/lon points. */
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

        /** Bearing in degrees from point 1 to point 2. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val dLon = Math.toRadians(lon2 - lon1)
            val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
            val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
                    Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
            return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
        }
    }
}
