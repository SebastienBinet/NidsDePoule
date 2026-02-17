package fr.nidsdepoule.app.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccelerationBufferTest {

    @Test
    fun `test empty buffer`() {
        val buffer = AccelerationBuffer()
        assertEquals(0, buffer.size)
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `test add and retrieve samples`() {
        val buffer = AccelerationBuffer(maxSize = 100)
        buffer.add(0, 1000, 50)
        buffer.add(20, 1100, 60)
        buffer.add(40, 900, 40)

        assertEquals(3, buffer.size)

        val snap = buffer.snapshot(step = 1)
        assertEquals(3, snap.size)
        assertEquals(1000, snap[0].verticalMg)
        assertEquals(1100, snap[1].verticalMg)
    }

    @Test
    fun `test circular buffer wraps`() {
        val buffer = AccelerationBuffer(maxSize = 5)
        for (i in 0 until 10) {
            buffer.add(i.toLong() * 20, 1000 + i, 50)
        }

        assertEquals(5, buffer.size)

        val snap = buffer.snapshot(step = 1)
        // Should contain the last 5 entries (i=5..9)
        assertEquals(1005, snap[0].verticalMg)
        assertEquals(1009, snap[4].verticalMg)
    }

    @Test
    fun `test downsampling`() {
        val buffer = AccelerationBuffer(maxSize = 100)
        for (i in 0 until 20) {
            buffer.add(i.toLong() * 20, 1000, 50)
        }

        val step4 = buffer.snapshot(step = 4)
        assertEquals(5, step4.size)  // 20 / 4 = 5

        val step2 = buffer.snapshot(step = 2)
        assertEquals(10, step2.size)  // 20 / 2 = 10
    }

    @Test
    fun `test clear`() {
        val buffer = AccelerationBuffer()
        buffer.add(0, 1000, 50)
        buffer.add(20, 1100, 60)
        buffer.clear()

        assertEquals(0, buffer.size)
    }
}
