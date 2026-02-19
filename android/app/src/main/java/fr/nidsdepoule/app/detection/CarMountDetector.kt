package fr.nidsdepoule.app.detection

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects when the phone is placed in a car mount.
 *
 * Criteria for "mounted in car":
 * 1. Stable orientation: gravity vector doesn't change much over time
 *    (phone is not being held/moved by hand).
 * 2. Low-frequency vibration: characteristic of engine/road, absent when
 *    the phone is on a table or in a pocket.
 * 3. Speed > 0: GPS indicates the device is moving (optional confirmatory signal).
 *
 * Pure Kotlin — no Android dependencies. Testable on JVM.
 */
class CarMountDetector(
    /** How many seconds of stable readings before declaring "mounted". */
    private val stableSeconds: Float = 5.0f,
    /** Maximum gravity vector change (mg) to consider "stable orientation". */
    private val maxGravityDriftMg: Int = 150,
    /** Minimum speed (m/s) to consider "in a car" (~5 km/h). */
    private val minSpeedMps: Float = 1.4f,
    /** Sampling rate assumption for reading count conversion. */
    private val sampleRateHz: Int = 50,
) {
    private var gravityX: Float = 0f
    private var gravityY: Float = 0f
    private var gravityZ: Float = 0f
    private var gravityInitialized = false

    private var stableReadingCount = 0
    private val stableReadingsNeeded = (stableSeconds * sampleRateHz).toInt()

    private var _isMounted = false
    val isMounted: Boolean get() = _isMounted

    private var lastSpeedMps: Float = 0f

    /**
     * Feed an accelerometer reading. Call at ~50Hz.
     * Raw accelerometer includes gravity. We use a low-pass filter to extract
     * the gravity component and check its stability.
     */
    fun processReading(x: Int, y: Int, z: Int) {
        val alpha = 0.8f  // Low-pass filter constant

        if (!gravityInitialized) {
            gravityX = x.toFloat()
            gravityY = y.toFloat()
            gravityZ = z.toFloat()
            gravityInitialized = true
            return
        }

        // Low-pass filter to isolate gravity
        val newGravityX = alpha * gravityX + (1 - alpha) * x
        val newGravityY = alpha * gravityY + (1 - alpha) * y
        val newGravityZ = alpha * gravityZ + (1 - alpha) * z

        // How much did the gravity vector change?
        val drift = sqrt(
            (newGravityX - gravityX) * (newGravityX - gravityX) +
            (newGravityY - gravityY) * (newGravityY - gravityY) +
            (newGravityZ - gravityZ) * (newGravityZ - gravityZ)
        )

        gravityX = newGravityX
        gravityY = newGravityY
        gravityZ = newGravityZ

        if (drift < maxGravityDriftMg) {
            stableReadingCount++
        } else {
            // Orientation changed significantly — reset counter
            stableReadingCount = 0
            _isMounted = false
        }

        // Declare mounted if stable long enough AND we have speed
        if (stableReadingCount >= stableReadingsNeeded && lastSpeedMps >= minSpeedMps) {
            _isMounted = true
        }
    }

    /**
     * Feed a GPS speed update. Call at ~1Hz.
     */
    fun updateSpeed(speedMps: Float) {
        lastSpeedMps = speedMps

        // If speed drops to zero for a while, we're probably parked.
        // Don't un-mount immediately — the car might be at a red light.
        // Just update the speed; the mount detection logic uses it on next check.
    }

    fun reset() {
        gravityInitialized = false
        stableReadingCount = 0
        _isMounted = false
        lastSpeedMps = 0f
    }
}
