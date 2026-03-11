package fr.nidsdepoule.app.detection

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for AccelRecorder — the circular buffer that stores
 * accelerometer readings for Hit waveform capture.
 */
class AccelRecorderTest {

    private lateinit var recorder: AccelRecorder

    @Before
    fun setup() {
        recorder = AccelRecorder(bufferSize = 100)
    }

    @Test
    fun `test empty buffer returns empty readings`() {
        val readings = recorder.recentReadings(5000)
        assertTrue(readings.isEmpty())
    }

    @Test
    fun `test single reading is returned`() {
        recorder.addReading(1000, 50)
        val readings = recorder.recentReadings(5000)
        assertEquals(1, readings.size)
        assertEquals(50, readings[0].magnitudeMg)
    }

    @Test
    fun `test recent readings filters by duration`() {
        // Add readings spanning 10 seconds (100ms apart)
        for (i in 0 until 100) {
            recorder.addReading(i * 100L, 50 + i)
        }

        // Last 5 seconds = timestamps 5000..9900
        val readings = recorder.recentReadings(5000)
        assertTrue(readings.isNotEmpty())
        assertTrue(readings.all { it.timestamp >= 4900 })
    }

    @Test
    fun `test buffer wraps at capacity`() {
        // Buffer size is 100; add 150 readings
        for (i in 0 until 150) {
            recorder.addReading(i * 20L, 50 + (i % 10))
        }

        // Should only have the last 100 readings
        val readings = recorder.recentReadings(Long.MAX_VALUE)
        assertEquals(100, readings.size)
        // First reading should be from timestamp 50*20=1000
        assertEquals(1000L, readings.first().timestamp)
    }

    @Test
    fun `test reset clears buffer`() {
        for (i in 0 until 50) {
            recorder.addReading(i * 20L, 50)
        }
        recorder.reset()
        val readings = recorder.recentReadings(5000)
        assertTrue(readings.isEmpty())
    }

    @Test
    fun `test 5 second capture for hit waveform`() {
        // Simulate 50Hz for 10 seconds = 500 readings
        // Use bufferSize large enough to hold them
        val bigRecorder = AccelRecorder(bufferSize = 1500)
        for (i in 0 until 500) {
            bigRecorder.addReading(i * 20L, 50 + (i % 20))
        }

        // Capture last 5 seconds
        val readings = bigRecorder.recentReadings(5000)
        assertTrue(readings.size in 240..260, "Should have ~250 readings for 5s at 50Hz, got ${readings.size}")
    }
}
