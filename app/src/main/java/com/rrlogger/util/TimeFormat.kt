package com.rrlogger.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 时间换算与显示的公共小工具。 */
object TimeFormat {

    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ymdHm = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
    private val friendly = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    fun clock(ms: Long): String = hms.format(Date(ms))

    fun fileNameStamp(ms: Long): String = ymdHm.format(Date(ms))

    fun friendly(ms: Long): String = friendly.format(Date(ms))

    /** 毫秒时长 → HH:mm:ss。 */
    fun duration(ms: Long): String {
        val total = TimeUnit.MILLISECONDS.toSeconds(ms).coerceAtLeast(0)
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            total / 3600, (total % 3600) / 60, total % 60
        )
    }

    /** 事件时间 tick（1/1024s）→ 毫秒，四舍五入。 */
    fun ticksToMs(ticks: Int): Int =
        ((ticks.toLong() * 1000L + 512L) / 1024L).toInt()
}
