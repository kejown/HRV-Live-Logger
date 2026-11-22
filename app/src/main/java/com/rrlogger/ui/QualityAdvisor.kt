package com.rrlogger.ui

/**
 * 数据质量分级：把 `droppedCount / rawRr` 换算成可操作的分级。
 *
 * 纯函数、无 Android 依赖，边界值直接由单测锁定。
 * UI 只负责把分级映射成文案与颜色。
 */
object QualityAdvisor {

    /** 原始样本少于此数时样本量不足，不做评价。 */
    const val MIN_SAMPLES = 20

    /** 丢弃率阈值：< 2% 优秀，< 5% 良好，< 15% 少量异常，其余异常较多。 */
    const val EXCELLENT_BELOW = 0.02
    const val GOOD_BELOW = 0.05
    const val FAIR_BELOW = 0.15

    enum class Level {
        /** 样本量不足，界面显示"等待数据"。 */
        IDLE,
        EXCELLENT,
        GOOD,
        FAIR,
        POOR
    }

    data class Verdict(val level: Level, val droppedRate: Double)

    /**
     * @param droppedCount 累计丢弃数
     * @param rawRr        累计原始样本数（含被丢弃的）
     */
    fun evaluate(droppedCount: Int, rawRr: Long): Verdict {
        if (rawRr < MIN_SAMPLES || rawRr <= 0L) return Verdict(Level.IDLE, 0.0)
        val rate = droppedCount.coerceAtLeast(0).toDouble() / rawRr.toDouble()
        val level = when {
            rate < EXCELLENT_BELOW -> Level.EXCELLENT
            rate < GOOD_BELOW -> Level.GOOD
            rate < FAIR_BELOW -> Level.FAIR
            else -> Level.POOR
        }
        return Verdict(level, rate)
    }
}
