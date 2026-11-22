package com.rrlogger.util

import android.util.Log

/**
 * 轻量日志封装。
 *
 * 关键不变量（违反即 bug）：
 *  - RR 值只能来自 heartBeatEventTime 差值
 *  - 非 Page 4 数据不得参与任何计算
 *  - ANT 回调线程不得做数据库 IO
 */
object Logs {
    private const val TAG = "RRLogger"
    private const val VERBOSE = false

    fun d(msg: String) = Log.d(TAG, msg)

    fun v(msg: String) {
        if (VERBOSE) Log.v(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)

    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
