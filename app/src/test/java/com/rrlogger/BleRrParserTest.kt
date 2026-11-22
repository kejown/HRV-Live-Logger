package com.rrlogger

import com.rrlogger.ant.RrCalculator
import com.rrlogger.ble.BleBeatValidator
import com.rrlogger.ble.BleRrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE Heart Rate Measurement (0x2A37) 解析测试。
 *
 * 帧格式（Bluetooth SIG）：
 *   byte0 Flags
 *     bit0 HR uint16 / bit3 EnergyExpended 存在 / bit4 RR 数组存在
 *   其后：HR → energy(可选) → RR uint16 数组
 */
class BleRrParserTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ---------------- flags 分支 ----------------

    @Test
    fun `uint8 heart rate only, no rr`() {
        // flags=0x00: HR uint8, 无 RR
        val f = BleRrParser.parse(bytes(0x00, 72))
        assertNotNull(f)
        assertEquals(72, f!!.heartRate)
        assertTrue(f.rrTicks.isEmpty())
        assertFalse(f.hasEnergyExpended)
        assertFalse(f.hrIsUint16)
    }

    @Test
    fun `uint16 heart rate`() {
        // flags=0x01: HR uint16
        val f = BleRrParser.parse(bytes(0x01, 0x2C, 0x01)) // 300
        assertNotNull(f)
        assertEquals(300, f!!.heartRate)
        assertTrue(f.hrIsUint16)
    }

    @Test
    fun `rr present with uint8 hr`() {
        // flags=0x10: RR 存在
        // HR=60, RR=[1024, 1000]
        val f = BleRrParser.parse(
            bytes(0x10, 60, 0x00, 0x04,  0xE8, 0x03)
        )
        assertNotNull(f)
        assertEquals(60, f!!.heartRate)
        assertEquals(listOf(1024, 1000), f.rrTicks)
    }

    @Test
    fun `energy expended field is skipped`() {
        // flags=0x18: energy + RR
        // HR=60, energy=0x0064(100), RR=[1024]
        val f = BleRrParser.parse(
            bytes(0x18, 60, 0x64, 0x00, 0x00, 0x04)
        )
        assertNotNull(f)
        assertEquals(60, f!!.heartRate)
        assertTrue(f.hasEnergyExpended)
        assertEquals(listOf(1024), f.rrTicks)
    }

    @Test
    fun `uint16 hr with energy and multiple rr`() {
        // flags=0x19: HR uint16 + energy + RR
        val f = BleRrParser.parse(
            bytes(0x19, 0x2C, 0x01, 0x00, 0x00, 0x00, 0x04, 0xE8, 0x03)
        )
        assertNotNull(f)
        assertEquals(300, f!!.heartRate)
        assertEquals(listOf(1024, 1000), f.rrTicks)
    }

    @Test
    fun `short frame returns null`() {
        assertNull(BleRrParser.parse(bytes(0x00)))
        assertNull(BleRrParser.parse(bytes()))
    }

    @Test
    fun `zero rr values are filtered out`() {
        // RR=0 是无效占位，必须剔除
        val f = BleRrParser.parse(bytes(0x10, 60, 0x00, 0x00, 0x00, 0x04))
        assertNotNull(f)
        assertEquals(listOf(1024), f!!.rrTicks)
    }

    @Test
    fun `odd trailing byte does not crash`() {
        // RR 数组应为偶数长度；多出的半截必须被忽略而不是抛异常
        val f = BleRrParser.parse(bytes(0x10, 60, 0x00, 0x04, 0x7F))
        assertNotNull(f)
        assertEquals(listOf(1024), f!!.rrTicks)
    }

    // ---------------- tick → ms 与 ANT+ 一致性 ----------------

    @Test
    fun `ble ticks to ms matches ant plus algorithm`() {
        for (t in listOf(1, 100, 307, 1000, 1024, 1036, 2048)) {
            assertEquals(
                "tick=$t 两种协议换算必须一致",
                RrCalculator.ticksToMs(t),
                BleRrParser.ticksToMs(t)
            )
        }
    }

    @Test
    fun `1024 ticks is one second`() {
        assertEquals(1000, BleRrParser.ticksToMs(1024))
    }

    @Test
    fun `doc wrap example still yields 1012ms`() {
        // 回绕只发生在 ANT+ 的 16 位 eventTime；BLE 直接给 RR，
        // 但 1036 tick 这个值的毫秒换算结果必须一致
        assertEquals(1012, BleRrParser.ticksToMs(1036))
    }
}

/** BLE 丢拍校验：只有生理过滤会丢弃；连续性只统计不丢。 */
class BleBeatValidatorTest {

    /** 构造一个 tick 值，使其换算后接近给定毫秒。 */
    private fun ticksFor(ms: Int): Int = (ms.toLong() * 1024L / 1000L).toInt()

    @Test
    fun `valid rr passes`() {
        val v = BleBeatValidator()
        val r = v.validate(ticksFor(1000))
        assertTrue(r is BleBeatValidator.Result.Valid)
        assertEquals(1000, (r as BleBeatValidator.Result.Valid).rrMs)
        assertEquals(0, v.drainDropped())
    }

    @Test
    fun `below 300ms is dropped`() {
        val v = BleBeatValidator()
        val r = v.validate(ticksFor(280))
        assertTrue("280ms 应被丢弃，实际 $r", r is BleBeatValidator.Result.OutOfRange)
        assertEquals(1, v.drainDropped())
    }

    @Test
    fun `above 2000ms is dropped`() {
        val v = BleBeatValidator()
        val r = v.validate(ticksFor(2100))
        assertTrue(r is BleBeatValidator.Result.OutOfRange)
        assertEquals(1, v.drainDropped())
    }

    @Test
    fun `boundaries are inclusive`() {
        val v = BleBeatValidator()
        assertTrue(v.validate(ticksFor(300)) is BleBeatValidator.Result.Valid)
        assertTrue(v.validate(ticksFor(2000)) is BleBeatValidator.Result.Valid)
    }

    /**
     * 关键设计：连续性突变**不丢数据**，只计数。
     * 设计文档明确「不做自动伪影校正，仅做生理范围过滤」，
     * 擅自删除真实的心率突变会让 Kubios 的分析失真。
     */
    @Test
    fun `sudden change is flagged but not dropped`() {
        val v = BleBeatValidator()
        v.validate(ticksFor(1000))
        val r = v.validate(ticksFor(1600)) // 变化 60% > 35%
        assertTrue("仍应通过", r is BleBeatValidator.Result.Valid)
        assertTrue("应被标记可疑", (r as BleBeatValidator.Result.Valid).isSuspect)
        assertEquals("不得丢弃", 0, v.drainDropped())
        assertEquals("可疑计数 +1", 1, v.drainSuspect())
    }

    @Test
    fun `small change is not suspect`() {
        val v = BleBeatValidator()
        v.validate(ticksFor(1000))
        val r = v.validate(ticksFor(1100)) // 10% < 35%
        assertTrue(r is BleBeatValidator.Result.Valid)
        assertFalse((r as BleBeatValidator.Result.Valid).isSuspect)
        assertEquals(0, v.drainSuspect())
    }

    @Test
    fun `reset clears state`() {
        val v = BleBeatValidator()
        v.validate(ticksFor(1000))
        v.validate(ticksFor(280)) // drop
        v.reset()
        assertEquals(0, v.drainDropped())
        assertEquals(0, v.drainSuspect())
        // reset 后首条不做连续性比较，不应标记可疑
        val r = v.validate(ticksFor(1600))
        assertTrue(r is BleBeatValidator.Result.Valid)
        assertFalse((r as BleBeatValidator.Result.Valid).isSuspect)
    }

    @Test
    fun `long steady run has no drops`() {
        val v = BleBeatValidator()
        var ok = 0
        repeat(600) { i ->
            // 60bpm 附近 ±10ms 抖动
            val ms = 1000 + (i % 20) - 10
            if (v.validate(ticksFor(ms)) is BleBeatValidator.Result.Valid) ok++
        }
        assertEquals(600, ok)
        assertEquals(0, v.drainDropped())
    }
}
