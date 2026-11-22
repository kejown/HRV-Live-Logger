package com.rrlogger.ant

import android.content.Context
import android.util.Log
import com.rrlogger.source.HeartRateSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 通过反射驱动 ANT+ 官方 SDK（antpluginlib，按 3.9.0 校对）。
 *
 * 为什么反射：SDK 的 AAR 不发布到公开 Maven，编译期不能硬依赖它的类。
 *
 * ────────────────────────────────────────────────────────────────
 * 以下签名均用 `javap` 对 antpluginlib_3-9-0.aar 逐一核对，
 * 并由 SdkReflectionContractTest 单元测试锁定：
 *
 * com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
 *   static PccReleaseHandle<AntPlusHeartRatePcc> requestAccess(
 *       Context bindToContext, int antDeviceNumber, int searchProximityThreshold,
 *       IPluginAccessResultReceiver, IDeviceStateChangeReceiver)
 *   void subscribeHeartRateDataEvent(IHeartRateDataReceiver)
 *   void subscribePage4AddtDataEvent(IPage4AddtDataReceiver)
 *   void releaseAccess()
 *   String getDeviceName()
 *
 * IPluginAccessResultReceiver:
 *   void onResultReceived(T pcc, RequestAccessResult, DeviceState)
 * IDeviceStateChangeReceiver:
 *   void onDeviceStateChange(DeviceState)
 * IHeartRateDataReceiver:
 *   void onNewHeartRateData(long estTimestamp, EnumSet<EventFlag>,
 *                           int computedHeartRate, long heartBeatCount,
 *                           BigDecimal heartBeatEventTime, DataState)
 * IPage4AddtDataReceiver:
 *   void onNewPage4AddtData(long estTimestamp, EnumSet<EventFlag>,
 *                           int manufacturerSpecificByte, BigDecimal previousHeartBeatEventTime)
 * ────────────────────────────────────────────────────────────────
 *
 * 关于「是否只处理 Page 4」：
 * 3.9.0 的 Page 4 附加回调里**没有 RrFlag**，也没有当前事件时间，无法用它取 RR。
 * 本实现的判据是：订阅 `subscribePage4AddtDataEvent` 收到标记 = 设备在发 Page 4；
 * 逐拍 RR 取自 `onNewHeartRateData` 的 heartBeatEventTime 差分（该字段本身就是
 * 逐拍事件时间，单位为 1/1024 秒，与 Page 4 的时序同源）。
 */
class SdkAntHeartRateSource(private val context: Context) : HeartRateSource {

    companion object {
        private const val TAG = "SdkAntSource"

        private const val PCC_CLASS = "com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc"
        private const val BASE_PCC = "com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc"
        private const val PAGE4_RECEIVER = "$PCC_CLASS\$IPage4AddtDataReceiver"
        private const val HR_RECEIVER = "$PCC_CLASS\$IHeartRateDataReceiver"
        private const val RESULT_RECEIVER = "$BASE_PCC\$IPluginAccessResultReceiver"
        private const val STATE_RECEIVER = "$BASE_PCC\$IDeviceStateChangeReceiver"

        /** deviceNumber = 0：连接第一个搜索到的设备。 */
        private const val ANT_DEVICE_NUMBER_FIRST_AVAILABLE = 0

        /** proximity = -1：使用插件里用户指定的默认搜索策略（官方推荐）。 */
        private const val PROXIMITY_USE_PLUGIN_DEFAULT = -1
    }

    override val kind = HeartRateSource.SourceKind.ANT_PLUS

    private val pccClass: Class<*>? = try {
        Class.forName(PCC_CLASS)
    } catch (_: Throwable) {
        null
    }

    private val parser = Page4RrParser()

    private var releaseHandle: Any? = null
    private var pcc: Any? = null
    private var listener: HeartRateSource.Listener? = null

    /** 诊断：Page 4 附加事件计数，用于判断设备是否真的在发逐拍数据。 */
    private var page4Markers = 0L
    private var rawSamples = 0L

    override fun diagnostic(): String = "Page4=$page4Markers raw=$rawSamples"

    override fun isAvailable(): HeartRateSource.Availability {
        val check = AntAvailability.check(context)
        val sdkPresent = pccClass != null

        val msg = when {
            !sdkPresent && !check.radioServiceInstalled ->
                "缺少 ANT+ SDK 与 ANT 服务。请把 antpluginlib_*.aar 放入 app/libs/，" +
                    "并在支持 ANT+ 的设备上运行。"
            !sdkPresent ->
                "缺少 ANT+ SDK：请把 antpluginlib_*.aar 放入 app/libs/ 后重新构建。"
            !check.radioServiceInstalled ->
                "本机未安装 ANT 服务（${AntAvailability.ANT_RADIO_SERVICE_PKG}），无法连接心率带。" +
                    "请改用 BLE 模式。"
            !check.pluginServiceInstalled ->
                "已安装 ANT 服务但缺少 ANT+ Plugin Service，建议安装后使用。"
            else -> null
        }

        return HeartRateSource.Availability(
            available = sdkPresent && check.radioServiceInstalled,
            message = msg,
            detail = buildString {
                append("SDK=").append(if (sdkPresent) "有" else "无")
                append(" · ARS=").append(if (check.radioServiceInstalled) "有" else "无")
                append(" · Plugins=").append(if (check.pluginServiceInstalled) "有" else "无")
            }
        )
    }

    override fun connect(listener: HeartRateSource.Listener) {
        this.listener = listener
        val cls = pccClass
        if (cls == null) {
            listener.onError("ANT+ SDK 未打包（antpluginlib_*.aar 缺失）", fatal = true)
            return
        }

        val avail = isAvailable()
        if (!avail.available) {
            listener.onError(avail.message ?: "ANT+ 不可用", fatal = true)
            return
        }

        parser.reset()
        page4Markers = 0
        rawSamples = 0

        val loader = cls.classLoader ?: javaClass.classLoader
        try {
            val resultReceiver = proxy(loader, RESULT_RECEIVER)
            val stateReceiver = proxy(loader, STATE_RECEIVER)
            if (resultReceiver == null || stateReceiver == null) {
                listener.onError("ANT+ SDK 版本不匹配：回调接口未找到", fatal = true)
                return
            }

            val method: Method = cls.methods.firstOrNull {
                it.name == "requestAccess" &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            } ?: run {
                listener.onError(
                    "ANT+ SDK 版本不匹配：requestAccess(Context,int,int,receiver,receiver) 未找到",
                    fatal = true
                )
                return
            }

            releaseHandle = method.invoke(
                null,
                context.applicationContext,
                ANT_DEVICE_NUMBER_FIRST_AVAILABLE,
                PROXIMITY_USE_PLUGIN_DEFAULT,
                resultReceiver,
                stateReceiver
            )
            listener.onSearching()
        } catch (t: Throwable) {
            Log.e(TAG, "requestAccess failed", t)
            listener.onError(
                "ANT+ 连接失败：${t.javaClass.simpleName}${t.message?.let { " - $it" } ?: ""}",
                fatal = true
            )
        }
    }

    private fun subscribeAll(pcc: Any) {
        this.pcc = pcc
        try {
            val loader = pcc.javaClass.classLoader ?: javaClass.classLoader
            val page4 = proxy(loader, PAGE4_RECEIVER)
            val hr = proxy(loader, HR_RECEIVER)

            pcc.javaClass.methods.firstOrNull { it.name == "subscribePage4AddtDataEvent" }
                ?.takeIf { page4 != null }
                ?.invoke(pcc, page4)

            pcc.javaClass.methods.firstOrNull { it.name == "subscribeHeartRateDataEvent" }
                ?.takeIf { hr != null }
                ?.invoke(pcc, hr)

            Log.d(TAG, "subscribed Page4 + HeartRate events")
        } catch (t: Throwable) {
            Log.e(TAG, "subscribe failed", t)
            listener?.onError("订阅数据事件失败：${t.message}", fatal = false)
        }
    }

    private fun proxy(loader: ClassLoader, iface: String): Any? = try {
        val ifaceClass = Class.forName(iface, false, loader)
        Proxy.newProxyInstance(loader, arrayOf(ifaceClass), InvocationHandler { _, method, args ->
            dispatch(method.name, args)
        })
    } catch (t: Throwable) {
        Log.w(TAG, "cannot proxy $iface: ${t.message}")
        null
    }

    private fun dispatch(name: String, args: Array<out Any?>?): Any? {
        val l = listener ?: return null
        try {
            when (name) {
                "onResultReceived" -> {
                    val pccObj = args?.getOrNull(0)
                    val resultName = enumName(args?.getOrNull(1))
                    when (resultName) {
                        "SUCCESS", "ALREADY_SUBSCRIBED" -> {
                            if (pccObj != null) subscribeAll(pccObj)
                            l.onSearching()
                        }
                        "DEPENDENCY_NOT_INSTALLED" ->
                            l.onError("缺少 ANT+ 插件依赖（DEPENDENCY_NOT_INSTALLED）", fatal = true)
                        "ADAPTER_NOT_DETECTED" ->
                            l.onError("本机没有可用的 ANT 适配器，请改用 BLE 模式", fatal = true)
                        "CHANNEL_NOT_AVAILABLE" ->
                            l.onError("ANT 通道不可用，可能被其他应用占用", fatal = false)
                        "DEVICE_ALREADY_IN_USE" ->
                            l.onError("心率带被其他应用占用", fatal = false)
                        "SEARCH_TIMEOUT" ->
                            l.onError("搜索超时：请确认心率带已佩戴并处于唤醒状态", fatal = false)
                        "USER_CANCELLED" -> l.onError("已取消连接", fatal = false)
                        "BAD_PARAMS" -> l.onError("连接参数错误（BAD_PARAMS）", fatal = false)
                        "OTHER_FAILURE", "UNRECOGNIZED" ->
                            l.onError("连接失败：$resultName", fatal = false)
                        else -> Log.d(TAG, "result=$resultName")
                    }
                }

                "onDeviceStateChange" -> {
                    when (val s = enumName(args?.getOrNull(0))) {
                        "TRACKING" -> l.onConnected(deviceName())
                        "SEARCHING", "PROCESSING_REQUEST" -> l.onSearching()
                        "DEAD", "CLOSED" -> l.onDisconnected()
                        else -> Log.d(TAG, "state=$s")
                    }
                }

                // Page 4 附加事件：仅证明设备在发 Page 4，不含 RR
                "onNewPage4AddtData" -> {
                    page4Markers++
                }

                // 逐拍心率：RR 的唯一来源
                "onNewHeartRateData" -> {
                    rawSamples++
                    val hr = (args?.getOrNull(2) as? Number)?.toInt() ?: return null
                    val beatCount = (args?.getOrNull(3) as? Number)?.toLong() ?: return null
                    val eventTimeRaw = args?.getOrNull(4)
                    val state = enumName(args?.getOrNull(5))

                    // 设备刚连上给的首帧占位值 / 零值，参与差分会产生巨大假 RR
                    if (state == "INITIAL_VALUE") return null
                    if (state == "AUTO_ZERO" || state == "ZERO_DETECTED") {
                        l.onDropped(1, "zero detected ($state)")
                        return null
                    }

                    val eventTime = bigDecimalToInt(eventTimeRaw) ?: return null
                    handleBeat(
                        eventTime = eventTime,
                        beatCount = (beatCount and 0xFF).toInt(),
                        heartRate = hr
                    )
                }

                "hashCode" -> return System.identityHashCode(this)
                "equals" -> return args?.getOrNull(0) === this
                "toString" -> return "AntCallbackProxy"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dispatch $name failed", t)
        }
        return null
    }

    /** 交给 Page4RrParser 做回绕差分 + 丢拍检测 + 生理过滤。 */
    private fun handleBeat(eventTime: Int, beatCount: Int, heartRate: Int) {
        val l = listener ?: return
        val result = parser.onSample(
            eventTime = eventTime,
            beatCount = beatCount,
            heartRate = heartRate,
            isPage4 = true
        )
        val droppedDelta = parser.drainDropped()

        when (result) {
            is RrParseResult.Valid -> {
                // 统一以 tick 交出，毫秒换算集中到 Manager 一处，避免两条协议算法分叉
                l.onValidRr(result.diffTicks, result.heartRate)
            }
            is RrParseResult.OutOfRange -> {
                l.onDropped(1, "out of range ${result.rrMs}ms")
            }
            is RrParseResult.DroppedBeat -> {
                if (droppedDelta > 0) l.onDropped(droppedDelta, "beat drop")
            }
            RrParseResult.Seeded -> Unit
        }
    }

    private fun deviceName(): String? = try {
        pcc?.let { fieldOrGetter(it, "deviceName") as? String }
    } catch (_: Throwable) {
        null
    }

    override fun disconnect() {
        try {
            releaseHandle?.let { h ->
                h.javaClass.methods.firstOrNull { it.name == "close" }?.invoke(h)
            }
            pcc?.let { p ->
                p.javaClass.methods.firstOrNull { it.name == "releaseAccess" }?.invoke(p)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        } finally {
            releaseHandle = null
            pcc = null
            parser.reset()
        }
    }

    override fun release() {
        disconnect()
        listener = null
    }

    // ---------------- 反射小工具 ----------------

    private fun enumName(o: Any?): String? = when {
        o == null -> null
        o.javaClass.isEnum -> (o as Enum<*>).name
        else -> o.toString()
    }

    /**
     * BigDecimal → 16 位无符号 tick。
     * SDK 用 BigDecimal 保留设备上报精度；RR 计算只需整数 tick，
     * 四舍五入到最近 tick 避免累计截断误差。
     */
    private fun bigDecimalToInt(v: Any?): Int? = when (v) {
        null -> null
        is java.math.BigDecimal ->
            v.setScale(0, java.math.RoundingMode.HALF_UP).toInt() and 0xFFFF
        is Number -> v.toInt() and 0xFFFF
        else -> v.toString().toDoubleOrNull()?.let { (it + 0.5).toInt() and 0xFFFF }
    }

    private fun fieldOrGetter(target: Any, name: String): Any? {
        val cap = name.replaceFirstChar { it.uppercase() }
        val methods = target.javaClass.methods.associateBy { it.name }
        (methods["get$cap"] ?: methods[name])?.let { m ->
            if (m.parameterCount == 0) {
                return try {
                    m.isAccessible = true
                    m.invoke(target)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }
}
