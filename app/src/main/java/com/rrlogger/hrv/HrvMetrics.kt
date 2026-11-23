package com.rrlogger.hrv

/**
 * 一次 HRV 计算结果（时域 + Poincaré）。
 *
 * 单位：RR/HRV 指标为毫秒，[meanHrBpm] 为 bpm，百分比为 0..100。
 * 计算只读 RR 序列，不修改、不写回任何记录数据。
 */
data class HrvMetrics(
    val samples: Int,
    val meanRrMs: Double,
    val meanHrBpm: Double,
    val sdnnMs: Double,
    val rmssdMs: Double,
    val pnn50Percent: Double,
    val cvnnPercent: Double,
    val sd1Ms: Double,
    val sd2Ms: Double,
    /** 相邻 RR 变化超过 [HrvCalculator.ARTIFACT_RATIO] 的次数（只统计，不删数据）。 */
    val artifactBeats: Int
) {
    /** 样本是否达到最低可信数量（少于该值时只显示"样本不足"，不给结论）。 */
    val reliable: Boolean get() = samples >= HrvCalculator.MIN_SAMPLES

    /** SD1/SD2 比值；SD2 为 0 时返回 0。 */
    val sd1Sd2Ratio: Double get() = if (sd2Ms > 0.0) sd1Ms / sd2Ms else 0.0
}
