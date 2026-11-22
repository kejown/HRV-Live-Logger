package com.rrlogger

import com.rrlogger.ant.Page4RrParser
import com.rrlogger.ant.RrCalculator
import com.rrlogger.ant.RrParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生理过滤边界：仅 300 <= rrMs <= 2000 有效。 */
class PhysiologicalFilterTest {

    private fun beatsFor(ms: Int): Int = (ms.toLong() * 1024L / 1000L).toInt()

    @Test
    fun `boundaries are inclusive`() {
        assertTrue(RrCalculator.isPhysiologicallyValid(300))
        assertTrue(RrCalculator.isPhysiologicallyValid(2000))
        assertTrue(RrCalculator.isPhysiologicallyValid(800))
    }

    @Test
    fun `just outside boundaries invalid`() {
        assertTrue(!RrCalculator.isPhysiologicallyValid(299))
        assertTrue(!RrCalculator.isPhysiologicallyValid(2001))
        assertTrue(!RrCalculator.isPhysiologicallyValid(0))
        assertTrue(!RrCalculator.isPhysiologicallyValid(-5))
        assertTrue(!RrCalculator.isPhysiologicallyValid(60000))
    }

    @Test
    fun `parser accepts exactly 300ms`() {
        val p = Page4RrParser()
        val ticks = 307 // 307*1000/1024 = 299.8 -> 300
        val rr = RrCalculator.ticksToMs(ticks)
        assertEquals(300, rr)

        p.onSample(eventTime = 1000, beatCount = 1, heartRate = 60, isPage4 = true)
        val r = p.onSample(eventTime = 1000 + ticks, beatCount = 2, heartRate = 200, isPage4 = true)
        assertTrue("300ms 应判为有效，实际 $r", r is RrParseResult.Valid)
        assertEquals(300, (r as RrParseResult.Valid).rrMs)
    }

    @Test
    fun `parser rejects just below 300ms`() {
        val p = Page4RrParser()
        val ticks = 306 // 306*1000/1024 = 298.8 -> 299
        val rr = RrCalculator.ticksToMs(ticks)
        assertEquals(299, rr)

        p.onSample(eventTime = 5000, beatCount = 10, heartRate = 220, isPage4 = true)
        val r = p.onSample(eventTime = 5000 + ticks, beatCount = 11, heartRate = 220, isPage4 = true)
        assertTrue("299ms 应被过滤，实际 $r", r is RrParseResult.OutOfRange)
    }

    @Test
    fun `parser accepts exactly 2000ms`() {
        val p = Page4RrParser()
        val ticks = 2048 // = 2000ms
        p.onSample(eventTime = 100, beatCount = 200, heartRate = 30, isPage4 = true)
        val r = p.onSample(eventTime = 100 + ticks, beatCount = 201, heartRate = 30, isPage4 = true)
        assertTrue(r is RrParseResult.Valid)
        assertEquals(2000, (r as RrParseResult.Valid).rrMs)
    }

    @Test
    fun `parser rejects just above 2000ms`() {
        val p = Page4RrParser()
        val ticks = 2049 // 2049*1000/1024 = 2000.97 -> 2001
        assertEquals(2001, RrCalculator.ticksToMs(ticks))
        p.onSample(eventTime = 100, beatCount = 200, heartRate = 25, isPage4 = true)
        val r = p.onSample(eventTime = 100 + ticks, beatCount = 201, heartRate = 25, isPage4 = true)
        assertTrue(r is RrParseResult.OutOfRange)
    }

    @Test
    fun `out of range increments dropped counter`() {
        val p = Page4RrParser()
        p.onSample(0, 1, 60, true)
        p.onSample(100, 2, 60, true)   // 97ms -> 无效
        assertEquals(1, p.drainDropped())
        assertEquals(0, p.drainDropped())
    }

    @Test
    fun `heart rate sanitized when absurd`() {
        assertEquals(0, RrCalculator.sanitizeHeartRate(0))
        assertEquals(0, RrCalculator.sanitizeHeartRate(255))
        assertEquals(72, RrCalculator.sanitizeHeartRate(72))
    }
}
