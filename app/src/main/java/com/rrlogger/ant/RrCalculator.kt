package com.rrlogger.ant

/**
 * Page 4 → RR 的纯计算部分。没有任何 Android / ANT+ 依赖，可直接单测。
 *
 * 规格（文档 4.1）：
 *  1. curr >= prev → diff = curr - prev
 *  2. curr <  prev → diff = curr + 65536 - prev   （16 位无符号回绕）
 *  3. rrMs = diff * 1000 / 1024，取整
 *  4. heartBeatCount 跳变 > 1 → 丢拍，丢弃该差值
 *  5. 仅 300 <= rrMs <= 2000 有效
 *  6. 首次只缓存事件时间，不产出 RR
 */
object RrCalculator {

    /** 事件时间单位：1/1024 秒。 */
    const val TICKS_PER_SECOND = 1024

    /** 16 位无符号模数。 */
    const val TICK_MODULUS = 65536

    /** 生理有效区间（ms，闭区间）。 */
    const val RR_MIN_MS = 300
    const val RR_MAX_MS = 2000

    /** 有效心率上限，用于过滤异常广播值（不作为 RR 有效性条件）。 */
    private const val HR_MAX_SANE = 240
    private const val HR_MIN_SANE = 20

    /**
     * ANT+ 心率数据帧里 0 表示"未测量"，255 (0xFF) 表示"无效/超出量程"。
     * 两者都必须清成 0，否则会污染会话平均心率。
     */
    private const val HR_INVALID_SENTINEL = 255

    /** 16 位无符号差值（含回绕）。必须落在 [0, 65535]。 */
    fun eventTimeDiff(curr: Int, prev: Int): Int {
        val c = curr and 0xFFFF
        val p = prev and 0xFFFF
        return if (c >= p) c - p else c + TICK_MODULUS - p
    }

    /**
     * 事件时间差值（1/1024s tick）→ 毫秒，**四舍五入**（不是向上取整）。
     *
     * 向上取整会给每个非整数毫秒都加 +1ms（长期均值系统性偏大约 0.5ms），
     * 与 README「用四舍五入而不是截断」的决策不一致；这里统一为最近整数。
     */
    fun ticksToMs(diffTicks: Int): Int {
        if (diffTicks <= 0) return 0
        return ((diffTicks.toLong() * 1000L + TICKS_PER_SECOND / 2L) / TICKS_PER_SECOND).toInt()
    }

    /**
     * ⚠️ 注意：Page 4 的 beatCount 是 8 位（0..255），不是 16 位。
     * 文档中的 "跳变 > 1 即丢拍" 只有在不跨越 255→0 时成立，
     * 否则每 256 拍会误丢一次。这里做 8 位回绕归一化，避免误判。
     */
    fun beatCountDelta(curr: Int, prev: Int): Int {
        val c = curr and 0xFF
        val p = prev and 0xFF
        return if (c >= p) c - p else c + 256 - p
    }

    fun isPhysiologicallyValid(rrMs: Int): Boolean = rrMs in RR_MIN_MS..RR_MAX_MS

    /** 设备广播心率做一次安全钳制，避免 0 / 255 污染均值。 */
    fun sanitizeHeartRate(hr: Int): Int =
        if (hr in HR_MIN_SANE..HR_MAX_SANE && hr != HR_INVALID_SENTINEL) hr else 0
}

/** 单次 Page 4 解析结果。 */
sealed interface RrParseResult {
    /** 首次样本：只缓存了事件时间。 */
    data object Seeded : RrParseResult

    /** 有效 RR。 */
    data class Valid(
        val rrMs: Int,
        val heartRate: Int,
        val diffTicks: Int
    ) : RrParseResult

    /** 丢拍（beatCount 跳变 > 1 或倒退）。 */
    data class DroppedBeat(val skippedBeats: Int) : RrParseResult

    /** RR 超出生理范围。 */
    data class OutOfRange(val rrMs: Int) : RrParseResult
}

/**
 * 有状态的 Page 4 解析器。
 *
 * 线程安全：内部加锁，ANT+ 回调线程与测试线程都可直接调用。
 * 一次会话对应一个实例；重连后应调用 [reset]，避免拿旧事件时间做差。
 */
class Page4RrParser {

    private val lock = Any()

    private var lastEventTime: Int? = null
    private var lastBeatCount: Int? = null

    /** 累加丢弃计数，交给 Service 读取后清零。 */
    private var dropped: Int = 0

    /**
     * @param eventTime   heartBeatEventTime（16 位无符号，1/1024s）
     * @param beatCount   heartBeatCount（8 位）
     * @param heartRate   设备广播瞬时心率
     * @param isPage4     RrFlag == DATA_SOURCE_PAGE_4 时为 true
     */
    fun onSample(
        eventTime: Int,
        beatCount: Int,
        heartRate: Int,
        isPage4: Boolean
    ): RrParseResult {
        // 硬约束 2：只认 Page 4，其他页数据一律丢弃且不改状态
        if (!isPage4) return RrParseResult.DroppedBeat(0)

        synchronized(lock) {
            val prevTime = lastEventTime
            val prevBeat = lastBeatCount
            lastEventTime = eventTime and 0xFFFF
            lastBeatCount = beatCount and 0xFF

            // 首次只缓存，不产出
            if (prevTime == null || prevBeat == null) return RrParseResult.Seeded

            val diffTicks = RrCalculator.eventTimeDiff(eventTime, prevTime)

            // 丢拍检测（8 位归一化）
            val delta = RrCalculator.beatCountDelta(beatCount, prevBeat)
            if (delta != 1) {
                dropped++
                // delta == 0：重复样本；delta > 1：中间丢拍。
                // 无论哪种，该差值都不是一个可靠的单拍间期，丢弃。
                return RrParseResult.DroppedBeat(skippedBeats = maxOf(delta - 1, 1))
            }

            val rrMs = RrCalculator.ticksToMs(diffTicks)
            if (!RrCalculator.isPhysiologicallyValid(rrMs)) {
                dropped++
                return RrParseResult.OutOfRange(rrMs)
            }

            return RrParseResult.Valid(
                rrMs = rrMs,
                heartRate = RrCalculator.sanitizeHeartRate(heartRate),
                diffTicks = diffTicks
            )
        }
    }

    /** 取走累计丢弃数。 */
    fun drainDropped(): Int = synchronized(lock) {
        val d = dropped
        dropped = 0
        d
    }

    fun droppedTotal(): Int = synchronized(lock) { dropped }

    /** 重连 / 换会话时调用，清空事件时间缓存。 */
    fun reset() = synchronized(lock) {
        lastEventTime = null
        lastBeatCount = null
        dropped = 0
    }
}
