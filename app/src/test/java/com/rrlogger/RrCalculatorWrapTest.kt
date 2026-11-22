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
}
