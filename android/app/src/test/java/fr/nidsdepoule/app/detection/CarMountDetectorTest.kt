package fr.nidsdepoule.app.detection

import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarMountDetectorTest {

    private lateinit var detector: CarMountDetector

    @Before
    fun setup() {
        detector = CarMountDetector(
            stableSeconds = 1.0f,  // Short for testing
            maxGravityDriftMg = 150,
            minSpeedMps = 1.4f,
            sampleRateHz = 50,
        )
    }

    @Test
    fun `test initially not mounted`() {
        assertFalse(detector.isMounted)
    }

    @Test
    fun `test stable orientation with speed becomes mounted`() {
        detector.updateSpeed(10f)  // Moving at 10 m/s

        // Send stable readings for 1 second at 50Hz
        for (i in 0 until 60) {
            detector.processReading(0, 0, 1000)  // Phone flat, gravity on Z
        }

        assertTrue(detector.isMounted, "Stable orientation + speed should be mounted")
    }

    @Test
    fun `test stable orientation without speed does not mount`() {
        detector.updateSpeed(0f)  // Not moving

        for (i in 0 until 60) {
            detector.processReading(0, 0, 1000)
        }

        assertFalse(detector.isMounted, "No speed should prevent mounting")
    }

    @Test
    fun `test unstable orientation resets`() {
        detector.updateSpeed(10f)

        // Build some stability
        for (i in 0 until 30) {
            detector.processReading(0, 0, 1000)
        }

        // Sudden large orientation change (phone picked up)
        detector.processReading(500, 500, 500)

        // Should reset
        assertFalse(detector.isMounted)
    }

    @Test
    fun `test reset clears state`() {
        detector.updateSpeed(10f)

        for (i in 0 until 60) {
            detector.processReading(0, 0, 1000)
        }
        assertTrue(detector.isMounted)

        detector.reset()
        assertFalse(detector.isMounted)
    }

    @Test
    fun `test road vibration does not unmount`() {
        detector.updateSpeed(12f)

        // Mount first
        for (i in 0 until 60) {
            detector.processReading(0, 0, 1000)
        }
        assertTrue(detector.isMounted)

        // Small vibrations (road bumps) should not unmount
        for (i in 0 until 50) {
            val noise = (Math.random() * 40 - 20).toInt()  // ±20 mg noise
            detector.processReading(noise, noise, 1000 + noise)
        }
        assertTrue(detector.isMounted, "Small vibrations should not unmount")
    }
}
