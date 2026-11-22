package com.rrlogger

import com.rrlogger.ble.BleRrParser
import com.rrlogger.ble.RrUnitVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * RR 单位判定测试。
 *
 * 背景：Bluetooth SIG 规定 RR-Interval 单位是 1/1024 秒，
 * 但部分国产固件实际发毫秒。两者差 2.34%，
 * 单看数值分不出来，必须用「RR 反推心率 vs 广播心率」交叉验证。
 */
class RrUnitVerifierTest {

    /** 造一个标准 tick 设备的帧：给定毫秒目标，反算设备应发的 tick。 */
    private fun tickRawFor(ms: Int): Int = (ms.toLong() * 1024L / 1000L).toInt()

    // ---------------- 标准设备（tick） ----------------

    @Test
    fun `standard tick device is detected as TICK`() {
        val v = RrUnitVerifier()
        // 60bpm: RR=1000ms → 设备发 1024 tick，广播心率 60
        repeat(20) { v.feed(1024, 60) }
        assertEquals(RrUnitVerifier.Unit.TICK, v.decidedUnit())
    }

    @Test
    fun `standard device at 75bpm detected as TICK`() {
        val v = RrUnitVerifier()
        // 75bpm → RR=800ms → 819.2 tick ≈ 819
        val raw = tickRawFor(800)
        repeat(20) { v.feed(raw, 75) }
        assertEquals(RrUnitVerifier.Unit.TICK, v.decidedUnit())
    }

    // ---------------- 非标设备（毫秒） ----------------

    @Test
    fun `nonstandard ms device is detected as MILLISECOND`() {
        val v = RrUnitVerifier()
        // 60bpm: 设备直接发 1000（毫秒），广播心率 60
        repeat(20) { v.feed(1000, 60) }
        assertEquals(
            "发毫秒的设备必须被识别出来",
            RrUnitVerifier.Unit.MILLISECOND,
            v.decidedUnit()
        )
    }

    @Test
    fun `nonstandard ms device at 75bpm detected`() {
        val v = RrUnitVerifier()
        repeat(20) { v.feed(800, 75) }
        assertEquals(RrUnitVerifier.Unit.MILLISECOND, v.decidedUnit())
    }

    // ---------------- 判定前的默认行为 ----------------

    @Test
    fun `undecided before enough samples`() {
        val v = RrUnitVerifier()
        repeat(5) { v.feed(1024, 60) }
        assertEquals(RrUnitVerifier.Unit.UNDECIDED, v.decidedUnit())
    }

    @Test
    fun `undecided falls back to tick`() {
        val v = RrUnitVerifier()
        // 未判定时按标准换算
        assertEquals(BleRrParser.ticksToMs(1024), v.toMs(1024))
    }

    // ---------------- 换算结果的量化差异 ----------------

    /**
     * 量化「单位判错」的代价：如果设备发毫秒却被当成 tick，
     * 1000ms 的真实 RR 会被记成 977ms，偏差 23ms（2.3%）。
     * 这就是「看起来不准」的典型来源。
     */
    @Test
    fun `unit mismatch causes measurable error`() {
        val v = RrUnitVerifier()
        // 设备发毫秒 1000，此时尚未判定 → 按 tick 换算
        repeat(3) { v.feed(1000, 60) }
        val wrongMs = v.toMs(1000)
        assertEquals("未判定时按 tick：1000 tick → 977ms", 977, wrongMs)
        assertEquals("真实值应为 1000ms", 1000, 1000)
        assertEquals("偏差 23ms", 23, abs(1000 - wrongMs))

        // 判定完成后纠正
        repeat(20) { v.feed(1000, 60) }
        assertEquals(RrUnitVerifier.Unit.MILLISECOND, v.decidedUnit())
        assertEquals("纠正后应原样返回毫秒", 1000, v.toMs(1000))
    }

    /**
     * 标准设备被误判成毫秒同样有害：1024 tick（=1000ms）会被当成 1024ms。
     */
    @Test
    fun `reverse mismatch also measurable`() {
        val v = RrUnitVerifier()
        repeat(20) { v.feed(1024, 60) }
        assertEquals(RrUnitVerifier.Unit.TICK, v.decidedUnit())
        assertEquals("1024 tick 应换算成 1000ms", 1000, v.toMs(1024))
    }

    // ---------------- 鲁棒性 ----------------

    @Test
    fun `invalid inputs are ignored`() {
        val v = RrUnitVerifier()
        repeat(30) { v.feed(0, 60) }
        repeat(30) { v.feed(1024, 0) }
        assertEquals(0, v.sampleCount())
        assertEquals(RrUnitVerifier.Unit.UNDECIDED, v.decidedUnit())
    }

    @Test
    fun `outlier artifacts do not poison decision`() {
        val v = RrUnitVerifier()
        // 大部分是正常标准帧
        repeat(15) { v.feed(1024, 60) }
        // 掺入明显伪影（心率与 RR 完全不自洽）
        repeat(10) { v.feed(300, 60) }
        assertEquals(
            "伪影不应污染判定",
            RrUnitVerifier.Unit.TICK,
            v.decidedUnit()
        )
    }

    @Test
    fun `reset clears decision`() {
        val v = RrUnitVerifier()
        repeat(20) { v.feed(1000, 60) }
        assertEquals(RrUnitVerifier.Unit.MILLISECOND, v.decidedUnit())
        v.reset()
        assertEquals(RrUnitVerifier.Unit.UNDECIDED, v.decidedUnit())
        assertEquals(0, v.sampleCount())
    }

    @Test
    fun `diagnostic string is readable`() {
        val v = RrUnitVerifier()
        repeat(20) { v.feed(1024, 60) }
        val d = v.diagnostic()
        assertTrue("诊断串应含样本数: $d", d.contains("n=20"))
        assertTrue("诊断串应含判定结果: $d", d.contains("TICK"))
    }

    // ---------------- 与既有换算的一致性 ----------------

    @Test
    fun `millisecond decision round trips through tick conversion`() {
        // ms → tick → ms 对整数值应无损（±1ms 内）
        for (ms in listOf(400, 600, 800, 1000, 1200, 1600, 2000)) {
            val tick = ((ms.toLong() * 1024L + 500) / 1000).toInt()
            val back = BleRrParser.ticksToMs(tick)
            assertTrue(
                "ms=$ms → tick=$tick → ${back}ms 偏差应 ≤1ms",
                abs(back - ms) <= 1
            )
        }
    }
}
