package com.rrlogger.hrv

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * HRV 时域指标计算（纯函数，无 Android 依赖）。
 *
 * 公式（与 Kubios 的口径一致）：
 *  - Mean RR：RR 均值；Mean HR = 60000 / Mean RR
 *  - SDNN：RR 的样本标准差（除以 n-1）
 *  - RMSSD：相邻差值平方均值的平方根
 *  - pNN50：相邻差值绝对值 > 50ms 的占比（严格大于）
 *  - SD1 = sqrt(0.5) × SDSD；SD2 = sqrt(2×SDNN² − 0.5×SDSD²)
 *  - CVNN = SDNN / Mean RR × 100%
 *
 * **不做伪影校正**：只额外统计"相邻变化 > 35%"的次数（[artifactBeats]），
 * 与 App 既有的"只统计不删数据"策略一致；是否可信由 UI 结合质量提示判断。
 */
object HrvCalculator {

    /** 低于这个样本数时指标噪声太大，UI 只提示"样本不足"。 */
    const val MIN_SAMPLES = 20

    /** 疑似伪影阈值：相邻 RR 变化超过较小值的 35%。 */
    const val ARTIFACT_RATIO = 0.35

    /** pNN50 的差值阈值（毫秒），严格大于才计数。 */
    const val PNN50_THRESHOLD_MS = 50.0

    /**
     * @param rrMs 有效 RR 序列（毫秒，按时间顺序）
     * @return 少于 2 个有效样本时返回 null
     */
    fun compute(rrMs: List<Int>): HrvMetrics? {
        val rr = rrMs.filter { it > 0 }
        if (rr.size < 2) return null

        val n = rr.size
        val meanRr = rr.sumOf { it.toDouble() } / n
        val sdnn = sampleSd(rr.map { it.toDouble() })

        val diffs = ArrayList<Double>(n - 1)
        var artifacts = 0
        for (i in 1 until n) {
            val diff = (rr[i] - rr[i - 1]).toDouble()
            diffs += diff
            val base = minOf(rr[i], rr[i - 1]).toDouble()
            if (base > 0 && abs(diff) / base > ARTIFACT_RATIO) artifacts++
        }

        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val pnn50 = diffs.count { abs(it) > PNN50_THRESHOLD_MS } * 100.0 / diffs.size
        val sdsd = sampleSd(diffs)
        val sd1 = sqrt(0.5) * sdsd
        val sd2 = sqrt(max(0.0, 2.0 * sdnn * sdnn - 0.5 * sdsd * sdsd))
        val meanHr = if (meanRr > 0) 60_000.0 / meanRr else 0.0
        val cvnn = if (meanRr > 0) sdnn / meanRr * 100.0 else 0.0

        return HrvMetrics(
            samples = n,
            meanRrMs = meanRr,
            meanHrBpm = meanHr,
            sdnnMs = sdnn,
            rmssdMs = rmssd,
            pnn50Percent = pnn50,
            cvnnPercent = cvnn,
            sd1Ms = sd1,
            sd2Ms = sd2,
            artifactBeats = artifacts
        )
    }

    /** 样本标准差（n-1）；少于两个值时返回 0。 */
    private fun sampleSd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.sum() / values.size
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / (values.size - 1))
    }
}
