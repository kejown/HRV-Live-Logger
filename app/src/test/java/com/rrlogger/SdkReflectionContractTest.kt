package com.rrlogger

import com.rrlogger.ant.RrCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 对 antpluginlib_3-9-0.aar 的反射契约测试。
 *
 * 这些断言全部针对**真实 SDK 类**：单元测试跑在 JVM 上，
 * app/libs 里的 AAR 已经被打进测试 classpath，所以 Class.forName 能拿到。
 * 目的是防止 SDK 升级后反射悄悄失效 —— 反射失败在运行期没有任何编译报错，
 * 只有测试能兜住。
 *
 * 如果这里挂了，说明 antpluginlib 版本变了，要按
 * SdkAntHeartRateSource 头部注释重新核对签名。
 */
class SdkReflectionContractTest {

    private val pccClass: Class<*> = Class.forName(
        "com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc"
    )

    @Test
    fun `pcc class loads`() {
        assertEquals(
            "com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc",
            pccClass.name
        )
    }

    /**
     * 本实现用的是 (Context, int deviceNumber, int proximity, r, r)。
     *
     * 不用 (Activity, Context, r, r) 版的原因：前台服务没有 Activity，
     * 而那一版会把 Activity 交给 RequestAccessResultHandler_UI 弹搜索界面。
     */
    @Test
    fun `context based requestAccess exists`() {
        val m = pccClass.methods.firstOrNull {
            it.name == "requestAccess" &&
                it.parameterTypes.size == 5 &&
                it.parameterTypes[0] == android.content.Context::class.java
        }
        assertNotNull("requestAccess(Context,int,int,receiver,receiver) 必须存在", m)

        val t = m!!.parameterTypes
        assertEquals("bindToContext", "android.content.Context", t[0].name)
        assertEquals("antDeviceNumber", "int", t[1].name)
        assertEquals("searchProximityThreshold", "int", t[2].name)
        assertTrue("resultReceiver", t[3].name.contains("IPluginAccessResultReceiver"))
        assertTrue("stateReceiver", t[4].name.contains("IDeviceStateChangeReceiver"))

        assertTrue(
            "返回类型应为 PccReleaseHandle，实际 ${m.returnType.name}",
            m.returnType.name.contains("PccReleaseHandle")
        )
    }

    /** SDK 不再提供 3 参数重载；如果有人按旧签名写反射，这里会立刻暴露。 */
    @Test
    fun `no three arg requestAccess overload`() {
        val m = pccClass.methods.firstOrNull {
            it.name == "requestAccess" && it.parameterTypes.size == 3
        }
        assertTrue("3 参数 requestAccess 不存在于 3.9.0", m == null)
    }

    @Test
    fun `release handle exposes close`() {
        val handleClass = Class.forName(
            "com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle"
        )
        assertNotNull(
            "PccReleaseHandle.close() 必须存在",
            handleClass.methods.firstOrNull { it.name == "close" }
        )
    }

    @Test
    fun `subscribe methods exist`() {
        assertNotNull(
            "subscribePage4AddtDataEvent",
            pccClass.methods.firstOrNull { it.name == "subscribePage4AddtDataEvent" }
        )
        assertNotNull(
            "subscribeHeartRateDataEvent",
            pccClass.methods.firstOrNull { it.name == "subscribeHeartRateDataEvent" }
        )
    }

    /**
     * 心率回调是 (long, EnumSet, int, long, BigDecimal, DataState) 裸参数，
     * 不是某个 Data 对象。早期按 data.heartBeatEventTime 取值的写法会全部拿不到数据。
     */
    @Test
    fun `heart rate receiver signature matches`() {
        val iface = Class.forName(
            "com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc\$IHeartRateDataReceiver"
        )
        val m = iface.methods.single()
        assertEquals("onNewHeartRateData", m.name)

        val t = m.parameterTypes
        assertEquals("参数个数应为 6，实际 ${t.size}", 6, t.size)
        assertEquals("estTimestamp", "long", t[0].name)
        assertTrue("eventFlags 应为 EnumSet", t[1].name.contains("EnumSet"))
        assertEquals("computedHeartRate", "int", t[2].name)
        assertEquals("heartBeatCount", "long", t[3].name)
        assertEquals("heartBeatEventTime", "java.math.BigDecimal", t[4].name)
        assertTrue("dataState 应为 DataState", t[5].name.contains("DataState"))
    }

    /** Page 4 附加回调：(long, EnumSet, int, BigDecimal)，没有 RrFlag。 */
    @Test
    fun `page4 receiver signature matches`() {
        val iface = Class.forName(
            "com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc\$IPage4AddtDataReceiver"
        )
        val m = iface.methods.single()
        assertEquals("onNewPage4AddtData", m.name)

        val t = m.parameterTypes
        assertEquals("参数个数应为 4，实际 ${t.size}", 4, t.size)
        assertEquals("long", t[0].name)
        assertTrue(t[1].name.contains("EnumSet"))
        assertEquals("manufacturerSpecificByte", "int", t[2].name)
        assertEquals("previousHeartBeatEventTime", "java.math.BigDecimal", t[3].name)
    }

    @Test
    fun `device state and result enums have expected constants`() {
        val state = Class.forName("com.dsi.ant.plugins.antplus.pcc.defines.DeviceState")
        for (n in listOf("TRACKING", "SEARCHING", "DEAD", "CLOSED", "PROCESSING_REQUEST")) {
            assertNotNull("DeviceState.$n", state.getField(n))
        }
        val result = Class.forName("com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult")
        for (n in listOf(
            "SUCCESS", "ALREADY_SUBSCRIBED", "DEPENDENCY_NOT_INSTALLED",
            "ADAPTER_NOT_DETECTED", "DEVICE_ALREADY_IN_USE", "SEARCH_TIMEOUT",
            "CHANNEL_NOT_AVAILABLE", "USER_CANCELLED"
        )) {
            assertNotNull("RequestAccessResult.$n", result.getField(n))
        }
    }

    @Test
    fun `data state enum includes initial value`() {
        val ds = Class.forName("com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc\$DataState")
        val names = ds.enumConstants.map { (it as Enum<*>).name }
        assertTrue("预期含 INITIAL_VALUE，实际 $names", names.contains("INITIAL_VALUE"))
    }

    // ---------------- BigDecimal → tick 换算 ----------------

    /** 复刻 SdkAntHeartRateSource.bigDecimalToInt 的语义。 */
    private fun bigDecimalToInt(v: Any?): Int? = when (v) {
        null -> null
        is BigDecimal -> v.setScale(0, java.math.RoundingMode.HALF_UP).toInt() and 0xFFFF
        is Number -> v.toInt() and 0xFFFF
        else -> v.toString().toDoubleOrNull()?.let { (it + 0.5).toInt() and 0xFFFF }
    }

    @Test
    fun `bigdecimal event time converts to tick`() {
        assertEquals(1234, bigDecimalToInt(BigDecimal("1234.0")))
        assertEquals(1235, bigDecimalToInt(BigDecimal("1234.5")))
        assertEquals(1234, bigDecimalToInt(BigDecimal("1234.4")))
        // 16 位无符号：65536 回绕到 0
        assertEquals(0, bigDecimalToInt(BigDecimal("65536")))
        assertEquals(65535, bigDecimalToInt(BigDecimal("65535")))
    }

    /** 真实链路：BigDecimal 事件时间 → 回绕差分 → 毫秒。 */
    @Test
    fun `real sdk bigdecimal path yields expected rr`() {
        val prev = bigDecimalToInt(BigDecimal("65000"))!!
        val curr = bigDecimalToInt(BigDecimal("500"))!!

        val diff = RrCalculator.eventTimeDiff(curr, prev)
        assertEquals(1036, diff)
        assertEquals(1012, RrCalculator.ticksToMs(diff))
    }
}
