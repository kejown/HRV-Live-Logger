package com.rrlogger.source

/**
 * 心率数据源的统一抽象（协议无关）。
 *
 * 两条实现：
 *  - [com.rrlogger.ant.SdkAntHeartRateSource]  ANT+ Page 4
 *  - [com.rrlogger.ble.BleHeartRateSource]     BLE Heart Rate Service (0x180D / 0x2A37)
 *
 * 为什么统一到 rrTicks：两种协议的 RR 原始单位**都是 1/1024 秒**
 * （ANT+ 是 heartBeatEventTime 的 tick 差值，BLE 是 RR-Interval 的 uint16），
 * 所以协议层直接把 tick 交出来，毫秒换算和生理过滤集中在一处做，不会出现
 * "一条路四舍五入、另一条路截断"这种隐蔽偏差。
 *
 * 丢拍/伪影检测也留在协议实现内部，因为判断依据不同：
 *  - ANT+ 有 heartBeatCount，能**确定**丢了几拍
 *  - BLE 没有计数器，只能靠 RR 连续性**推断**异常
 * 两者都通过 [Listener.onDropped] 上报，但含义要在统计里区分（见 README）。
 */
interface HeartRateSource {

    val kind: SourceKind

    /** 环境是否具备该协议的工作条件。 */
    fun isAvailable(): Availability

    /**
     * 诊断信息（可显示在界面上）。默认空串。
     * BLE 实现会返回 RR 单位判定结果——这是排查「数据不准」的关键线索。
     */
    fun diagnostic(): String = ""

    /**
     * 开始连接。回调可能在任意线程触发，实现方负责切线程。
     * 只有通过协议层校验的 RR 才通过 [Listener.onValidRr] 上报。
     */
    fun connect(listener: Listener)

    fun disconnect()

    fun release()

    enum class SourceKind(val label: String) {
        ANT_PLUS("ANT+"),
        BLE("BLE")
    }

    data class Availability(
        val available: Boolean,
        val message: String?,
        /** 人类可读的细节，用于界面横幅（可为空）。 */
        val detail: String = ""
    )

    interface Listener {
        fun onSearching()

        fun onConnected(deviceName: String?)

        /**
         * 一条通过校验的 RR。
         * @param rrTicks R-R 间期，单位 1/1024 秒
         * @param heartRate 设备广播瞬时心率（已做合理范围钳制，无效时为 0）
         */
        fun onValidRr(rrTicks: Int, heartRate: Int)

        /**
         * 本次回调丢弃了 [count] 条样本。
         * @param reason 仅用于日志/诊断，统计时统一累加
         */
        fun onDropped(count: Int, reason: String? = null)

        fun onDisconnected()

        /** [fatal] 为 true 表示环境层面不可用，UI 应禁用开始按钮。 */
        fun onError(message: String, fatal: Boolean)
    }
}
