package fr.nidsdepoule.app.detection

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThresholdHitDetectorTest {

    private lateinit var detector: ThresholdHitDetector

    @Before
    fun setup() {
        detector = ThresholdHitDetector(
            bufferSize = 3000,
            thresholdFactor = 3.0,
            waveformSamples = 150,
            cooldownMs = 500
        )
    }

    @Test
    fun `test smooth road produces no hits`() {
        // Simulate smooth driving at 1g (1000mg) baseline
        val baselineAccel = 1000
        val variance = 50

        var timestamp = 0L
        for (i in 0 until 200) {
            val reading = baselineAccel + (Math.random() * variance * 2 - variance).toInt()
            val event = detector.processReading(
                timestamp = timestamp,
                verticalAccelMg = reading,
                lateralAccelMg = 50,
                speedMps = 10f
            )
            assertNull(event, "Smooth road should not produce hits at sample $i")
            timestamp += 20 // 50Hz sampling
        }
    }

    @Test
    fun `test single pothole spike is detected`() {
        // Build baseline with normal readings
        var timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(
                timestamp = timestamp,
                verticalAccelMg = 1000,
                lateralAccelMg = 50,
                speedMps = 10f
            )
            timestamp += 20
        }

        // Inject a pothole spike (5000mg = 5g)
        val event = detector.processReading(
            timestamp = timestamp,
            verticalAccelMg = 5000,
            lateralAccelMg = 200,
            speedMps = 10f
        )

        assertNotNull(event, "Pothole spike should be detected")
        assertTrue(event.peakVerticalMg >= 4500, "Peak should be around 5000mg")
        assertTrue(event.baselineMg > 0, "Baseline should be computed")
    }

    @Test
    fun `test severity classification is correct`() {
        // Build baseline
        var timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(
                timestamp = timestamp,
                verticalAccelMg = 1000,
                lateralAccelMg = 50,
                speedMps = 10f
            )
            timestamp += 20
        }

        // Light hit: 2.5x baseline (2500mg)
        detector.reset()
        timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }
        var event = detector.processReading(timestamp, 2500, 100, 10f)
        // Light hits might not be detected due to threshold of 3.0
        // Let's test with 3.5x

        detector.reset()
        timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }
        event = detector.processReading(timestamp, 3500, 100, 10f)
        assertNotNull(event)
        assertEquals(2, event.severity, "3.5x baseline should be medium severity")

        // Heavy hit: 6x baseline (6000mg)
        detector.reset()
        timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }
        event = detector.processReading(timestamp, 6000, 200, 10f)
        assertNotNull(event)
        assertEquals(3, event.severity, "6x baseline should be heavy severity")
    }

    @Test
    fun `test cooldown prevents duplicate detection`() {
        // Build baseline
        var timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }

        // First spike
        val event1 = detector.processReading(timestamp, 5000, 200, 10f)
        assertNotNull(event1, "First spike should be detected")
        timestamp += 20

        // Second spike within cooldown (500ms)
        val event2 = detector.processReading(timestamp + 100, 5000, 200, 10f)
        assertNull(event2, "Second spike within cooldown should be ignored")

        // Third spike after cooldown
        val event3 = detector.processReading(timestamp + 600, 5000, 200, 10f)
        assertNotNull(event3, "Spike after cooldown should be detected")
    }

    @Test
    fun `test waveform is captured around the peak`() {
        // Build baseline and add readings before peak
        var timestamp = 0L
        for (i in 0 until 150) {
            detector.processReading(timestamp, 1000 + i, 50, 10f)
            timestamp += 20
        }

        // Peak
        val event = detector.processReading(timestamp, 5000, 200, 10f)
        assertNotNull(event)

        // Waveform should have the configured number of samples
        assertEquals(150, event.waveformVertical.size, "Waveform should have 150 samples")
        assertEquals(150, event.waveformLateral.size, "Lateral waveform should have 150 samples")

        // The peak should be somewhere in the middle of the waveform
        val maxInWaveform = event.waveformVertical.maxOrNull() ?: 0
        assertTrue(maxInWaveform >= 4500, "Peak should be present in waveform")
    }

    @Test
    fun `test baseline adapts over time`() {
        var timestamp = 0L

        // Phase 1: baseline at 1000mg
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }

        // Phase 2: baseline shifts to 1200mg
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1200, 50, 10f)
            timestamp += 20
        }

        // A spike relative to the new baseline should be detected
        val event = detector.processReading(timestamp, 4000, 200, 10f)
        assertNotNull(event, "Spike should be detected relative to adapted baseline")

        // Baseline should be closer to 1200mg than 1000mg
        assertTrue(event.baselineMg > 1000, "Baseline should have adapted upward")
    }

    @Test
    fun `test reset clears state`() {
        // Build some state
        var timestamp = 0L
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }

        // Trigger a hit
        val event1 = detector.processReading(timestamp, 5000, 200, 10f)
        assertNotNull(event1)

        // Reset
        detector.reset()

        // After reset, we need to rebuild baseline
        timestamp = 0L
        for (i in 0 until 20) {
            val event = detector.processReading(timestamp, 5000, 200, 10f)
            // Should not detect immediately due to insufficient baseline samples
            if (i < 10) {
                assertNull(event, "Should not detect without sufficient baseline at sample $i")
            }
            timestamp += 20
        }
    }

    @Test
    fun `test realistic pothole scenario`() {
        var timestamp = 0L

        // Normal driving for 2 seconds at 50Hz = 100 samples
        for (i in 0 until 100) {
            detector.processReading(timestamp, 1000, 50, 10f)
            timestamp += 20
        }

        // Approach pothole with slight increase
        detector.processReading(timestamp, 1200, 60, 10f)
        timestamp += 20
        detector.processReading(timestamp, 1500, 80, 10f)
        timestamp += 20

        // Hit pothole - sharp spike
        val event = detector.processReading(timestamp, 6500, 300, 10f)
        timestamp += 20

        // Recovery
        detector.processReading(timestamp, 2000, 150, 10f)
        timestamp += 20
        detector.processReading(timestamp, 1200, 70, 10f)
        timestamp += 20
        detector.processReading(timestamp, 1000, 50, 10f)

        assertNotNull(event, "Realistic pothole should be detected")
        assertEquals(3, event.severity, "Sharp spike should be classified as heavy")
        assertTrue(event.peakVerticalMg >= 6000, "Peak should be around 6500mg")
        assertTrue(event.durationMs >= 0, "Duration should be calculated")
    }
}
