package fr.nidsdepoule.app.reporting

import org.junit.Test
import kotlin.test.assertEquals

class DataUsageTrackerTest {

    @Test
    fun `test initial state is zero`() {
        val tracker = DataUsageTracker()
        assertEquals(0f, tracker.mbUploadThisWeek())
        assertEquals(0f, tracker.mbDownloadThisWeek())
        assertEquals(0f, tracker.mbUploadThisMonth())
        assertEquals(0f, tracker.mbDownloadThisMonth())
    }

    @Test
    fun `test recording upload and download bytes`() {
        val tracker = DataUsageTracker()
        tracker.record(bytesSent = 1024 * 1024, bytesReceived = 2 * 1024 * 1024)

        assertEquals(1.0f, tracker.mbUploadThisWeek())
        assertEquals(2.0f, tracker.mbDownloadThisWeek())
        assertEquals(1.0f, tracker.mbUploadThisMonth())
        assertEquals(2.0f, tracker.mbDownloadThisMonth())
    }

    @Test
    fun `test month counter accumulates`() {
        val tracker = DataUsageTracker()
        tracker.record(bytesSent = 1024 * 1024, bytesReceived = 512 * 1024)
        tracker.record(bytesSent = 1024 * 1024, bytesReceived = 512 * 1024)

        assertEquals(2.0f, tracker.mbUploadThisMonth())
        assertEquals(1.0f, tracker.mbDownloadThisMonth())
    }

    @Test
    fun `test month reset clears month counters`() {
        val tracker = DataUsageTracker()
        tracker.record(bytesSent = 1024 * 1024, bytesReceived = 1024 * 1024)
        assertEquals(1.0f, tracker.mbUploadThisMonth())

        tracker.resetMonth()
        assertEquals(0f, tracker.mbUploadThisMonth())
        assertEquals(0f, tracker.mbDownloadThisMonth())
    }

    @Test
    fun `test week reset clears week counters`() {
        val tracker = DataUsageTracker()
        tracker.record(bytesSent = 1024 * 1024, bytesReceived = 1024 * 1024)
        assertEquals(1.0f, tracker.mbUploadThisWeek())

        tracker.resetWeek()
        assertEquals(0f, tracker.mbUploadThisWeek())
        assertEquals(0f, tracker.mbDownloadThisWeek())
        // Month should still have data
        assertEquals(1.0f, tracker.mbUploadThisMonth())
    }

    @Test
    fun `test restore full state`() {
        val tracker = DataUsageTracker()
        tracker.restore(
            weekUp = 1024 * 1024,
            weekDown = 2 * 1024 * 1024,
            weekStart = 1000L,
            monthUp = 5L * 1024 * 1024,
            monthDown = 10L * 1024 * 1024,
            monthStart = 2000L,
        )

        assertEquals(1.0f, tracker.mbUploadThisWeek())
        assertEquals(2.0f, tracker.mbDownloadThisWeek())
        assertEquals(5.0f, tracker.mbUploadThisMonth())
        assertEquals(10.0f, tracker.mbDownloadThisMonth())
    }

    @Test
    fun `test restoreMonth backward compat`() {
        val tracker = DataUsageTracker()
        tracker.restoreMonth(5 * 1024 * 1024, 1000L)

        assertEquals(5.0f, tracker.mbUploadThisMonth())
    }

    @Test
    fun `test record with zero bytesReceived`() {
        val tracker = DataUsageTracker()
        tracker.record(bytesSent = 1024)

        assertEquals(0f, tracker.mbDownloadThisWeek())
        assertEquals(0f, tracker.mbDownloadThisMonth())
    }
}
