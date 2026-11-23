package com.rrlogger

import com.rrlogger.hrv.HrvCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * HRV 时域公式。
 *
 * 这些数字会直接显示在界面和会话摘要里，所以用可手算的序列把公式钉死：
 *  - 恒定 RR：所有变异性指标必须为 0；
 *  - 交替 RR：RMSSD 等于相邻差、pNN50 = 100%；
 *  - pNN50 边界：恰好 50ms 不算，51ms 才算；
 *  - 伪影计数只统计不删数据；
 *  - 样本数 < 20 时只标 unreliable，不返回 null。
 */
class HrvCalculatorTest {

    private fun constant(ms: Int, n: Int) = List(n) { ms }

    @Test
    fun `empty or single sample returns null`() {
        assertNull(HrvCalculator.compute(emptyList()))
        assertNull(HrvCalculator.compute(listOf(1000)))
        assertNull(HrvCalculator.compute(listOf(0, 0)))
    }

    @Test
    fun `constant rr has zero variability and 60 bpm`() {
        val m = HrvCalculator.compute(constant(1000, 30))!!
        assertEquals(30, m.samples)
        assertEquals(1000.0, m.meanRrMs, 1e-9)
        assertEquals(60.0, m.meanHrBpm, 1e-9)
        assertEquals(0.0, m.sdnnMs, 1e-9)
        assertEquals(0.0, m.rmssdMs, 1e-9)
        assertEquals(0.0, m.pnn50Percent, 1e-9)
        assertEquals(0.0, m.sd1Ms, 1e-9)
        assertEquals(0.0, m.sd2Ms, 1e-9)
        assertEquals(0, m.artifactBeats)
        assertTrue(m.reliable)
    }

    @Test
    fun `alternating rr gives rmssd equal to the step and pnn50 hundred percent`() {
        // 1000/1100 交替 20 拍：相邻差恒为 ±100ms。
        val rr = List(20) { if (it % 2 == 0) 1000 else 1100 }
        val m = HrvCalculator.compute(rr)!!
        assertEquals(1050.0, m.meanRrMs, 1e-9)
        assertEquals(100.0, m.rmssdMs, 1e-9)
        assertEquals(100.0, m.pnn50Percent, 1e-9)
        // 样本标准差：一半偏离 -50，一半 +50，n=20。
        assertEquals(50.0 * kotlin.math.sqrt(20.0 / 19.0), m.sdnnMs, 1e-9)
        // 交替序列的 Poincaré 图退化成一条线：SD2 应为 0（样本标准差用 n-1，留 0.5ms 余量）。
        assertTrue("sd2=${m.sd2Ms}", abs(m.sd2Ms) < 0.5)
        assertEquals(0, m.artifactBeats)
    }

    @Test
    fun `pnn50 uses strict greater than fifty milliseconds`() {
        val exact = HrvCalculator.compute(List(20) { if (it % 2 == 0) 1000 else 1050 })!!
        assertEquals(0.0, exact.pnn50Percent, 1e-9)

        val over = HrvCalculator.compute(List(20) { if (it % 2 == 0) 1000 else 1051 })!!
        assertEquals(100.0, over.pnn50Percent, 1e-9)
    }

    @Test
    fun `poincare identity sd1 squared plus sd2 squared equals twice sdnn squared`() {
        val rr = listOf(
            800, 812, 795, 806, 820, 790, 802, 815, 788, 799,
            810, 792, 805, 818, 795, 801, 807, 793, 816, 804
        )
        val m = HrvCalculator.compute(rr)!!
        val lhs = m.sd1Ms * m.sd1Ms + m.sd2Ms * m.sd2Ms
        val rhs = 2.0 * m.sdnnMs * m.sdnnMs
        assertEquals(rhs, lhs, 1e-6)
    }

    @Test
    fun `mean hr is derived from mean rr`() {
        val m = HrvCalculator.compute(constant(800, 20))!!
        assertEquals(75.0, m.meanHrBpm, 1e-9)
        assertEquals(0.0, m.cvnnPercent, 1e-9)
    }

    @Test
    fun `artifact count flags a big jump but keeps the data`() {
        // 19 个 1000ms 后接一个 1500ms：只有最后一对差值超过 35%。
        val rr = List(20) { if (it < 19) 1000 else 1500 }
        val m = HrvCalculator.compute(rr)!!
        assertEquals(20, m.samples)
        assertEquals(1, m.artifactBeats)
    }

    @Test
    fun `fewer than twenty samples is computed but marked unreliable`() {
        val m = HrvCalculator.compute(constant(1000, 19))!!
        assertFalse(m.reliable)
        assertEquals(19, m.samples)

        val ok = HrvCalculator.compute(constant(1000, 20))!!
        assertTrue(ok.reliable)
    }

    @Test
    fun `cvnn is sdnn over mean rr in percent`() {
        val rr = List(20) { if (it % 2 == 0) 1000 else 1100 }
        val m = HrvCalculator.compute(rr)!!
        assertEquals(m.sdnnMs / m.meanRrMs * 100.0, m.cvnnPercent, 1e-9)
    }
}
