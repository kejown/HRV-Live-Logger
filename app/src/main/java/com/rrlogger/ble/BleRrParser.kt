package com.rrlogger.ble

/**
 * BLE Heart Rate Measurement (0x2A37) 帧解析。
 *
 * 规格：Bluetooth SIG「Heart Rate Measurement」characteristic
 *
 *   byte 0: Flags
 *     bit0    : 0 = Heart Rate Value uint8，1 = uint16
 *     bit1..2 : Sensor Contact Status
 *     bit3    : 1 = Energy Expended 字段存在（uint16）
 *     bit4    : 1 = RR-Interval 数组存在（uint16 数组）
 *
 *   其后依序为：heart rate → energy expended（可选）→ RR 数组
 *
 * ⚠️ RR 单位是 **1/1024 秒**（与 ANT+ Page 4 的 heartBeatEventTime 同源）。
 * 因此换算成毫秒的方式和 ANT+ 完全一致：rrMs = ticks * 1000 / 1024。
 *
 * 这段是纯函数，不依赖 Android BLE API，可直接单测。
 */
object BleRrParser {

    const val TICKS_PER_SECOND = 1024

    data class Frame(
        val heartRate: Int,
        /** RR 原始 tick（1/1024s）。空表示该帧不含 RR。 */
        val rrTicks: List<Int>,
        /** 是否含能量消耗字段（解析偏移用，外部一般不需要）。 */
        val hasEnergyExpended: Boolean,
        /** 心率值是否为 uint16 编码。 */
        val hrIsUint16: Boolean
    )

    /**
     * @param data characteristic 的原始 value
     * @return 解析结果；数据太短或格式非法时返回 null
     */
    fun parse(data: ByteArray): Frame? {
        if (data.size < 2) return null

        val flags = data[0].toInt() and 0xFF
        val hrIsUint16 = (flags and 0x01) != 0
        val hasEnergy = (flags and 0x08) != 0
        val hasRr = (flags and 0x10) != 0

        var idx = 1

        // ---- Heart Rate ----
        val heartRate: Int = if (hrIsUint16) {
            if (idx + 1 >= data.size) return null
            val v = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
            idx += 2
            v
        } else {
            if (idx >= data.size) return null
            val v = data[idx].toInt() and 0xFF
            idx += 1
            v
        }

        // ---- Energy Expended（可选，纯跳过）----
        if (hasEnergy) {
            if (idx + 1 > data.size - 1) {
                // 字段不完整，但心率已经拿到了，按无 RR 返回
                return Frame(heartRate, emptyList(), hasEnergy, hrIsUint16)
            }
            idx += 2
        }

        // ---- RR-Interval 数组 ----
        val rr = ArrayList<Int>(4)
        if (hasRr) {
            while (idx + 1 < data.size) {
                val ticks = (data[idx].toInt() and 0xFF) or
                    ((data[idx + 1].toInt() and 0xFF) shl 8)
                if (ticks > 0) rr.add(ticks)
                idx += 2
            }
        }

        return Frame(heartRate, rr, hasEnergy, hrIsUint16)
    }

    /** RR tick → 毫秒，四舍五入（与 ANT+ 侧算法保持一致）。 */
    fun ticksToMs(ticks: Int): Int {
        if (ticks <= 0) return 0
        return ((ticks.toLong() * 1000L + TICKS_PER_SECOND / 2) / TICKS_PER_SECOND).toInt()
    }
}
