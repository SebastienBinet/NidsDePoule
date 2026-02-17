package fr.nidsdepoule.app.reporting

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataUsageTrackerTest {

    @Test
    fun `test initial state is zero`() {
        val tracker = DataUsageTracker()
        assertEquals(0f, tracker.kbLastMinute())
        assertEquals(0f, tracker.mbLastHour())
        assertEquals(0f, tracker.mbThisMonth())
    }

    @Test
    fun `test recording bytes updates all counters`() {
        val tracker = DataUsageTracker()
        tracker.record(1024)  // 1 KB

        assertEquals(1.0f, tracker.kbLastMinute())
        assertTrue(tracker.mbLastHour() > 0)
        assertTrue(tracker.mbThisMonth() > 0)
    }

    @Test
    fun `test month counter accumulates`() {
        val tracker = DataUsageTracker()
        tracker.record(1024 * 1024)  // 1 MB
        tracker.record(1024 * 1024)  // 1 MB

        assertEquals(2.0f, tracker.mbThisMonth())
    }

    @Test
    fun `test month reset clears month counter`() {
        val tracker = DataUsageTracker()
        tracker.record(1024 * 1024)
        assertEquals(1.0f, tracker.mbThisMonth())

        tracker.resetMonth()
        assertEquals(0f, tracker.mbThisMonth())
    }

    @Test
    fun `test restore month state`() {
        val tracker = DataUsageTracker()
        tracker.restoreMonth(5 * 1024 * 1024, 1000L)

        assertEquals(5.0f, tracker.mbThisMonth())
    }

    @Test
    fun `test old entries are pruned from sliding window`() {
        var fakeTime = 0L
        val tracker = DataUsageTracker(nowMs = { fakeTime })

        // Record at time 0
        tracker.record(1024)
        assertEquals(1.0f, tracker.kbLastMinute())

        // Advance past 1 minute
        fakeTime = 61_000
        assertEquals(0f, tracker.kbLastMinute(), "Old entries should fall out of minute window")

        // But still in hour window
        assertTrue(tracker.bytesLastHour() > 0)

        // Advance past 1 hour
        fakeTime = 3_601_000
        assertEquals(0L, tracker.bytesLastHour())
    }

    @Test
    fun `test multiple records in time window`() {
        var fakeTime = 0L
        val tracker = DataUsageTracker(nowMs = { fakeTime })

        tracker.record(500)
        fakeTime = 10_000
        tracker.record(300)
        fakeTime = 30_000
        tracker.record(200)

        // All three in last minute
        assertEquals(1000L, tracker.bytesLastMinute())

        // Advance so first record drops out
        fakeTime = 61_000
        assertEquals(500L, tracker.bytesLastMinute())
    }
}
