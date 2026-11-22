package com.rrlogger

import com.rrlogger.ui.QualityAdvisor
import com.rrlogger.ui.QualityAdvisor.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据质量分级的边界值。
 *
 * 这些阈值直接决定界面上"优秀 / 良好 / 少量异常 / 异常较多"的判定，
 * 以及用户是否被建议重新佩戴胸带，所以把每个边界的两侧都锁死。
 */
class QualityAdvisorTest {

    private fun level(dropped: Int, raw: Long): Level =
        QualityAdvisor.evaluate(dropped, raw).level

    @Test
    fun `fewer than twenty samples is idle`() {
        assertEquals(Level.IDLE, level(0, 0))
        assertEquals(Level.IDLE, level(10, 1))
        assertEquals(Level.IDLE, level(5, 19))
    }

    @Test
    fun `twenty samples is enough to judge`() {
        assertEquals(Level.EXCELLENT, level(0, 20))
    }

    @Test
    fun `two percent boundary belongs to good`() {
        assertEquals(Level.EXCELLENT, level(199, 10_000)) // 1.99%
        assertEquals(Level.GOOD, level(2, 100))           // 2.00%
    }

    @Test
    fun `five percent boundary belongs to fair`() {
        assertEquals(Level.GOOD, level(49, 1_000))  // 4.9%
        assertEquals(Level.FAIR, level(50, 1_000))  // 5.0%
    }

    @Test
    fun `fifteen percent boundary belongs to poor`() {
        assertEquals(Level.FAIR, level(149, 1_000)) // 14.9%
        assertEquals(Level.POOR, level(150, 1_000)) // 15.0%
    }

    @Test
    fun `rate is reported as a ratio with the raw sample denominator`() {
        val verdict = QualityAdvisor.evaluate(250, 1_000)
        assertEquals(Level.POOR, verdict.level)
        assertEquals(0.25, verdict.droppedRate, 1e-9)
    }

    @Test
    fun `negative dropped count is treated as zero`() {
        val verdict = QualityAdvisor.evaluate(-5, 100)
        assertEquals(Level.EXCELLENT, verdict.level)
        assertEquals(0.0, verdict.droppedRate, 1e-9)
    }
}
