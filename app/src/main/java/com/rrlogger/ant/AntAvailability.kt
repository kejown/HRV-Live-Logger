package com.rrlogger.ant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * ANT+ 环境可用性检测。
 *
 * ANT+ 需要系统里有一个 ANT Radio Service（com.dsi.ant.service.socket）在跑。
 * 老机型（三星/LG 等）内置；新机型没有的话需装 ANT+ Plugin Service，
 * 且没有硬件时插件也连不上 —— 所以两件事都要查：
 *   1) 服务包是否安装
 *   2) 是否存在 ANT 硬件（通过 AntPlusManager 实际打开通道再确认，见 [AntRuntimeStatus]）
 */
object AntAvailability {

    const val ANT_RADIO_SERVICE_PKG = "com.dsi.ant.service.socket"
    const val ANT_PLUGIN_SERVICE_PKG = "com.dsi.ant.plugins.antplus"

    data class CheckResult(
        val radioServiceInstalled: Boolean,
        val pluginServiceInstalled: Boolean
    ) {
        val available: Boolean get() = radioServiceInstalled
    }

    fun check(context: Context): CheckResult {
        val pm = context.packageManager
        return CheckResult(
            radioServiceInstalled = isInstalled(pm, ANT_RADIO_SERVICE_PKG),
            pluginServiceInstalled = isInstalled(pm, ANT_PLUGIN_SERVICE_PKG)
        )
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** 蓝牙是否开着（ANT+ 走 BT 射频，部分平台要求蓝牙开启）。 */
    fun hintMessage(r: CheckResult): String? = when {
        r.available && r.pluginServiceInstalled -> null
        r.available && !r.pluginServiceInstalled ->
            "已检测到 ANT 服务，但缺少 ANT+ Plugin Service，请安装后再使用。"
        !r.available ->
            "本机未检测到 ANT 服务（com.dsi.ant.service.socket）。" +
                "设备无内置 ANT+ 时，需外接 ANT+ USB/适配器或用支持 ANT+ 的手机。"
        else -> null
    }
}
