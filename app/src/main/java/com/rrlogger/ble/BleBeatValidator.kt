package com.rrlogger.ble

import com.rrlogger.ant.RrCalculator

/**
 * BLE 逐拍校验。
 *
 * ⚠️ 与 ANT+ 的**本质差异**，必须理解清楚再用：
 *
 *  | | ANT+ Page 4 | BLE 0x2A37 |
 *  |---|---|---|
 *  | 心跳计数器 | 有 heartBeatCount，能**确定**丢了几拍 | **没有** |
 *  | 丢拍检测 | 计数器跳变 > 1 即丢弃 | 无法直接检测 |
 *  | 本类对应物 | Page4RrParser | BleBeatValidator |
 *
 * 因此 BLE 侧的 droppedCount **只统计"超出生理范围"的样本**，
 * 不含"丢拍"——因为根本没有依据判断是否丢拍。
 *
 * 另外保留一个 [suspectCount] 计数：相邻 RR 变化率超过阈值时累加，
 * **但不丢弃数据**。理由：设计文档 1.2 明确「不做自动伪影校正，仅做生理范围过滤」，
 * 而 HRV 分析里真实的心率突变（起身、冲刺起步）会被误判成伪影，
 * 擅自删除会破坏 RR 序列的时间结构，反而让 Kubios 的分析失真。
 * 该计数只作为诊断信息给用户看。
 */
class BleBeatValidator {

    companion object {
        /** 相邻 RR 变化率超过此比例即视为"可疑"（仅统计，不丢弃）。 */
        const val SUSPECT_RATIO = 0.35

        /** 连续多少次可疑才认为真的有问题（避免单次抖动误报）。 */
        const val SUSPECT_STREAK_ALERT = 3
    }

    private var lastRrMs: Int = 0
    private var dropped: Int = 0
    private var suspect: Int = 0
    private var suspectStreak: Int = 0

    /** 上一次同包内的 RR，用于同包连续性判断。 */
    private val lock = Any()

    sealed interface Result {
        /** 通过校验。 */
        data class Valid(val rrMs: Int, val isSuspect: Boolean) : Result

        /** 超出生理范围，丢弃。 */
        data class OutOfRange(val rrMs: Int) : Result
    }

    /**
     * 校验一条 RR。
     * @param rrTicks 原始 tick（1/1024 秒）
     */
    fun validate(rrTicks: Int): Result {
        val rrMs = BleRrParser.ticksToMs(rrTicks)

        synchronized(lock) {
            if (!RrCalculator.isPhysiologicallyValid(rrMs)) {
                dropped++
                return Result.OutOfRange(rrMs)
            }

            // 连续性检查：仅统计，不丢弃
            val prev = lastRrMs
            var isSuspect = false
            if (prev > 0) {
                val delta = kotlin.math.abs(rrMs - prev).toDouble() / prev.toDouble()
                if (delta > SUSPECT_RATIO) {
                    suspect++
                    suspectStreak++
                    isSuspect = true
                } else {
                    suspectStreak = 0
                }
            }
            lastRrMs = rrMs
            return Result.Valid(rrMs, isSuspect)
        }
    }

    /** 取走并清零丢弃计数。 */
    fun drainDropped(): Int = synchronized(lock) {
        val d = dropped
        dropped = 0
        d
    }

    fun drainSuspect(): Int = synchronized(lock) {
        val s = suspect
        suspect = 0
        s
    }

    fun suspectStreakLength(): Int = synchronized(lock) { suspectStreak }

    fun reset() = synchronized(lock) {
        lastRrMs = 0
        dropped = 0
        suspect = 0
        suspectStreak = 0
    }
}
