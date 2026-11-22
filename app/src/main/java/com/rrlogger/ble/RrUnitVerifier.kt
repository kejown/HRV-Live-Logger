package com.rrlogger.ble

/**
 * RR 单位一致性校验器。
 *
 * ── 为什么需要它 ──
 * Bluetooth SIG 规定 Heart Rate Measurement 的 RR-Interval 单位是
 * **1/1024 秒**（uint16）。但国内部分心率带固件（包括某些双模胸带的 BLE 分支）
 * 实际发送的是**毫秒整数**。
 *
 * 两种假设的换算差 2.34%：
 *   tick 假设：1024 → 1000ms
 *   ms   假设：1024 → 1024ms
 *
 * 单看 RR 数值无法区分（静息 60bpm 时两种假设的原始值都在 1000 附近），
 * 但可以用**设备自己广播的心率**做交叉验证：
 *   若 RR 真是 tick，则 60000 / (rr*1000/1024) 应等于广播心率
 *   若 RR 真是 ms，  则 60000 / rr            应等于广播心率
 * 哪个假设的推算值更贴近广播心率，哪个就是对的。
 *
 * 这个校验完全被动：不改数据，只在内部累计判定结果，
 * 供 [BleHeartRateSource] 决定是否启用单位纠正。
 */
class RrUnitVerifier {

    companion object {
        /** 判定窗口：至少累积这么多帧才下结论。 */
        const val MIN_SAMPLES = 12

        /** 相对误差低于此值视为一致。 */
        const val MATCH_TOLERANCE = 0.02

        /** 一个假设的误差比另一个低这么多倍，才认为有明确优劣。 */
        const val DECISION_RATIO = 3.0
    }

    enum class Unit {
        /** RR 是 1/1024 秒（Bluetooth SIG 标准）。 */
        TICK,

        /** RR 是毫秒（部分非标固件）。 */
        MILLISECOND,

        /** 样本不足，尚未判定。默认按标准走。 */
        UNDECIDED
    }

    private val lock = Any()

    private var tickErrSum = 0.0
    private var msErrSum = 0.0
    private var samples = 0

    private var decided: Unit = Unit.UNDECIDED

    /** 供 UI/日志展示的诊断串。 */
    fun diagnostic(): String = synchronized(lock) {
        val n = samples
        if (n == 0) return "无样本"
        "n=$n tickErr=${"%.4f".format(tickErrSum / n)} " +
            "msErr=${"%.4f".format(msErrSum / n)} decided=$decided"
    }

    /**
     * 喂一帧用于判定。
     *
     * @param rrRaw 设备上报的原始 RR 值（未做任何单位换算）
     * @param broadcastHr 同一帧里的广播心率；<=0 时跳过
     */
    fun feed(rrRaw: Int, broadcastHr: Int) {
        if (rrRaw <= 0 || broadcastHr <= 0) return

        val hrAsTick = 60_000.0 / (rrRaw.toDouble() * 1000.0 / 1024.0)
        val hrAsMs = 60_000.0 / rrRaw.toDouble()

        // 归一化相对误差，避免心率高低影响权重
        val base = broadcastHr.toDouble()
        val eTick = kotlin.math.abs(hrAsTick - base) / base
        val eMs = kotlin.math.abs(hrAsMs - base) / base

        // 明显不合理的样本（伪影/丢拍）不参与统计，否则会污染判定
        if (eTick > 0.5 && eMs > 0.5) return

        synchronized(lock) {
            tickErrSum += eTick
            msErrSum += eMs
            samples++

            if (decided == Unit.UNDECIDED && samples >= MIN_SAMPLES) {
                val avgTick = tickErrSum / samples
                val avgMs = msErrSum / samples

                decided = when {
                    avgTick <= MATCH_TOLERANCE && avgTick <= avgMs -> Unit.TICK
                    avgMs <= MATCH_TOLERANCE && avgMs * DECISION_RATIO < avgTick -> Unit.MILLISECOND
                    // 两边误差都大：说明广播心率与 RR 不自洽，保持标准假设
                    else -> Unit.TICK
                }
            }
        }
    }

    fun decidedUnit(): Unit = synchronized(lock) { decided }

    fun sampleCount(): Int = synchronized(lock) { samples }

    /** 把原始值按判定结果换算成毫秒。 */
    fun toMs(rrRaw: Int): Int = when (decidedUnit()) {
        Unit.MILLISECOND -> rrRaw
        Unit.TICK, Unit.UNDECIDED -> BleRrParser.ticksToMs(rrRaw)
    }

    fun reset() = synchronized(lock) {
        tickErrSum = 0.0
        msErrSum = 0.0
        samples = 0
        decided = Unit.UNDECIDED
    }
}
