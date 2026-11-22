package com.rrlogger.ui

import android.content.Context

/**
 * 界面偏好：只放少量开关，用 SharedPreferences 就够，不引 DataStore。
 *
 * 目前两个键：
 *  - [focusMode]     专注模式（全屏只保留心率、时长与打点）
 *  - [keepScreenOn]  记录时保持屏幕常亮
 *
 * 区间条开关属于 P1，等区间条视图落地时再加键，避免出现"有开关没功能"。
 */
class DisplayPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("rrlogger_display", Context.MODE_PRIVATE)

    var focusMode: Boolean
        get() = sp.getBoolean(KEY_FOCUS, false)
        set(value) {
            sp.edit().putBoolean(KEY_FOCUS, value).apply()
        }

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN, false)
        set(value) {
            sp.edit().putBoolean(KEY_KEEP_SCREEN, value).apply()
        }

    private companion object {
        const val KEY_FOCUS = "focus_mode"
        const val KEY_KEEP_SCREEN = "keep_screen_on"
    }
}
