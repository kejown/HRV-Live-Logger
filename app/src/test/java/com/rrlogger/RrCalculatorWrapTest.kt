package com.rrlogger

import com.rrlogger.ant.Page4RrParser
import com.rrlogger.ant.RrCalculator
import com.rrlogger.ant.RrParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回绕计算 —— 文档硬约束 7。
 * prev = 65000, curr = 500 → diff = 500 + 65536 - 65000 = 1036
 */
class RrCalculatorWrapTest {

    @Test
    fun `doc example wrap gives 1036 ticks`() {
        val diff = RrCalculator.eventTimeDiff(curr = 500, prev = 65000)
        assertEquals(1036, diff)
    }

    @Test
    fun `doc example wrap converts to 1012 ms by nearest rounding`() {
        // 1036 * 1000 / 1024 = 1011.71875
        // 文档写"取整"。这里用四舍五入 → 1012，向上取整也是 1012，两者一致。
        assertEquals(1012, RrCalculator.ticksToMs(1036))
    }

    @Test
    fun `normal forward diff`() {
        assertEquals(1024, RrCalculator.eventTimeDiff(curr = 2048, prev = 1024))
        assertEquals(512, RrCalculator.eventTimeDiff(curr = 1536, prev = 1024))
    }

    @Test
    fun `equal timestamps give zero diff`() {
        assertEquals(0, RrCalculator.eventTimeDiff(curr = 777, prev = 777))
    }

    @Test
    fun `full modulus wrap`() {
        // 65535 -> 0 只走 1 tick，不能算成 65536
        assertEquals(1, RrCalculator.eventTimeDiff(curr = 0, prev = 65535))
    }

    @Test
    fun `values above 16 bits are masked`() {
        // 事件时间是 16 位无符号，高位必须丢弃
        assertEquals(
            RrCalculator.eventTimeDiff(500, 65000),
            RrCalculator.eventTimeDiff(500 + 0x10000, 65000)
        )
    }

    @Test
    fun `ticks to ms rounding boundary`() {
        assertEquals(0, RrCalculator.ticksToMs(0))
        // 1 tick = 0.9765ms → 四舍五入 1ms
        assertEquals(1, RrCalculator.ticksToMs(1))
        // 1024 ticks = 1000ms
        assertEquals(1000, RrCalculator.ticksToMs(1024))
        // 1000 ticks = 976.5625 → 977
        assertEquals(977, RrCalculator.ticksToMs(1000))
        // 100 ticks = 97.65 → 98
        assertEquals(98, RrCalculator.ticksToMs(100))
    }

    @Test
    fun `one second at 60bpm is 1024 ticks`() {
        assertEquals(1000, RrCalculator.ticksToMs(1024))
    }

    @Test
    fun `ticks to ms rounds to nearest not ceiling`() {
        // 1023 tick = 999.02 ms → 999；向上取整会得到 1000。
        assertEquals(999, RrCalculator.ticksToMs(1023))
        // 2047 tick = 1999.02 ms → 1999；向上取整会得到 2000。
        assertEquals(1999, RrCalculator.ticksToMs(2047))
        // 512 / 1536 tick 正好落在整数毫秒上，任何实现都得这两个值。
        assertEquals(500, RrCalculator.ticksToMs(512))
        assertEquals(1500, RrCalculator.ticksToMs(1536))
    }

    @Test
    fun `rounding error stays within half a millisecond for every 16 bit diff`() {
        var maxError = 0.0
        var worstTicks = 0
        for (ticks in 1..RrCalculator.TICK_MODULUS - 1) {
            val exactMs = ticks * 1000.0 / RrCalculator.TICKS_PER_SECOND
            val error = kotlin.math.abs(RrCalculator.ticksToMs(ticks) - exactMs)
            if (error > maxError) {
                maxError = error
                worstTicks = ticks
            }
        }
        // 最近整数取整的最坏误差是 0.5ms（向上取整会接近 1ms）。
        assertTrue(
            "worst rounding error ${maxError}ms at $worstTicks ticks",
            maxError <= 0.5 + 1e-9
        )
    }
}
