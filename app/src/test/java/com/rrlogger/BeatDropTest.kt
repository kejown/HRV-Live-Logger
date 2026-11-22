package com.rrlogger

import com.rrlogger.ant.Page4RrParser
import com.rrlogger.ant.RrCalculator
import com.rrlogger.ant.RrParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 丢拍检测：heartBeatCount 跳变 > 1 时丢弃该差值。 */
class BeatDropTest {

    private val oneSecond = 1024 // 1024 ticks = 1000ms

    @Test
    fun `first page4 sample only seeds`() {
        val p = Page4RrParser()
        val r = p.onSample(eventTime = 1234, beatCount = 7, heartRate = 60, isPage4 = true)
        assertEquals(RrParseResult.Seeded, r)
        assertEquals(0, p.drainDropped())
    }

    @Test
    fun `consecutive beat count produces valid rr`() {
        val p = Page4RrParser()
        p.onSample(0, 10, 60, true)
        val r = p.onSample(oneSecond, 11, 60, true)
        assertTrue(r is RrParseResult.Valid)
        assertEquals(1000, (r as RrParseResult.Valid).rrMs)
    }

    @Test
    fun `skipped beat is dropped and counted`() {
        val p = Page4RrParser()
        p.onSample(0, 10, 60, true)
        // 中间少了一拍：10 -> 12
        val r = p.onSample(2 * oneSecond, 12, 60, true)
        assertTrue(r is RrParseResult.DroppedBeat)
        assertTrue((r as RrParseResult.DroppedBeat).skippedBeats >= 1)
        assertEquals(1, p.drainDropped())
    }

    @Test
    fun `multiple skipped beats single increment per sample`() {
        val p = Page4RrParser()
        p.onSample(0, 10, 60, true)
        val r = p.onSample(5 * oneSecond, 15, 60, true)
        assertTrue(r is RrParseResult.DroppedBeat)
        assertEquals(4, (r as RrParseResult.DroppedBeat).skippedBeats)
        assertEquals(1, p.drainDropped())
    }

    @Test
    fun `duplicate beat count is dropped`() {
        val p = Page4RrParser()
        p.onSample(0, 10, 60, true)
        val r = p.onSample(500, 10, 60, true)
        assertTrue(r is RrParseResult.DroppedBeat)
        assertEquals(1, p.drainDropped())
    }

    @Test
    fun `beat count wraps 255 to 0 without false drop`() {
        val p = Page4RrParser()
        p.onSample(0, 255, 60, true)
        val r = p.onSample(oneSecond, 0, 60, true)
        assertTrue(
            "255 -> 0 是正常翻转，不应判丢拍，实际 $r",
            r is RrParseResult.Valid
        )
        assertEquals(1000, (r as RrParseResult.Valid).rrMs)
        assertEquals(0, p.drainDropped())
    }

    @Test
    fun `beat count wraps and skips one is still dropped`() {
        val p = Page4RrParser()
        p.onSample(0, 255, 60, true)
        // 255 -> 1 中间确实漏了 0
        val r = p.onSample(oneSecond, 1, 60, true)
        assertTrue(r is RrParseResult.DroppedBeat)
        assertEquals(1, p.drainDropped())
    }

    @Test
    fun `beat count delta helper handles wrap`() {
        assertEquals(1, RrCalculator.beatCountDelta(0, 255))
        assertEquals(2, RrCalculator.beatCountDelta(1, 255))
        assertEquals(1, RrCalculator.beatCountDelta(11, 10))
        assertEquals(0, RrCalculator.beatCountDelta(10, 10))
    }

    @Test
    fun `non page4 sample does not touch state`() {
        val p = Page4RrParser()
        // 非 Page4 不播种
        p.onSample(1000, 10, 60, isPage4 = false)
        // 第一条 Page4 仍应是首样本
        assertEquals(RrParseResult.Seeded, p.onSample(2000, 11, 60, isPage4 = true))
    }

    @Test
    fun `reset clears event time cache`() {
        val p = Page4RrParser()
        p.onSample(0, 10, 60, true)
        p.reset()
        assertEquals(RrParseResult.Seeded, p.onSample(oneSecond, 11, 60, true))
    }

    @Test
    fun `long run at 60bpm accumulates all beats`() {
        val p = Page4RrParser()
        var beats = 0
        p.onSample(0, 0, 60, true)
        for (i in 1..600) {
            val r = p.onSample(i * oneSecond, i % 256, 60, true)
            if (r is RrParseResult.Valid) beats++
        }
        // 600 拍中跨过 2 次 255->0 翻转，必须全部有效
        assertEquals(600, beats)
        assertEquals(0, p.droppedTotal())
    }
}
