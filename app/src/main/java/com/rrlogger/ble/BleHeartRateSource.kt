package com.rrlogger.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.rrlogger.source.HeartRateSource
import java.util.UUID

/**
 * BLE 心率带数据源（Heart Rate Service 0x180D / Heart Rate Measurement 0x2A37）。
 *
 * 与 ANT+ 实现的差异：
 *  - 不需要任何系统级服务，Android 原生 BLE API 即可
 *  - RR 直接从 characteristic value 里读（uint16 数组，单位 1/1024s）
 *  - 没有心跳计数器，丢拍检测退化为生理范围过滤（详见 [BleBeatValidator]）
 *
 * 连接流程：扫描（按 0x180D service UUID 过滤）→ connectGatt →
 * onServicesDiscovered → 开 0x2A37 通知 → 写 CCCD → 收 onCharacteristicChanged。
 *
 * 注意 GATT 操作必须在主线程发起，且回调也在 Binder 线程，所以内部统一 post 到主线程。
 */
class BleHeartRateSource(
    private val context: Context,
    /** 优先匹配的设备名（包含匹配，忽略大小写）。为空则连接第一个广播 0x180D 的设备。 */
    private val preferredName: String? = null
) : HeartRateSource {

    companion object {
        private const val TAG = "BleHeartRateSource"

        val SERVICE_HR: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val CHAR_HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 扫描超时。 */
        private const val SCAN_TIMEOUT_MS = 30_000L

        /** 连接超时。 */
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }

    override val kind = HeartRateSource.SourceKind.BLE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val validator = BleBeatValidator()

    /**
     * RR 单位校验：标准是 1/1024s，但部分固件发毫秒。
     * 判定前按标准走，样本足够后自动纠正。
     */
    private val unitVerifier = RrUnitVerifier()

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var listener: HeartRateSource.Listener? = null

    private var connected = false
    private var scanning = false
    private var targetName: String? = null

    /** 最近一次心率，供连接后无 RR 帧时兜底显示。 */
    private var lastHeartRate = 0

    private var lastLoggedUnit: RrUnitVerifier.Unit? = null

    // ================= 可用性 =================

    override fun isAvailable(): HeartRateSource.Availability {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ad = bm?.adapter
        adapter = ad

        if (ad == null) {
            return HeartRateSource.Availability(
                available = false,
                message = "本机没有蓝牙适配器，无法使用 BLE 心率带。"
            )
        }
        if (!ad.isEnabled) {
            return HeartRateSource.Availability(
                available = false,
                message = "蓝牙未开启，请在系统设置里打开蓝牙后重试。"
            )
        }

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return HeartRateSource.Availability(
                available = false,
                message = "缺少蓝牙权限：${missing.joinToString()}。请允许后重试。"
            )
        }

        return HeartRateSource.Availability(
            available = true,
            message = null,
            detail = "BLE 就绪 · ${ad.name ?: ad.address}"
        )
    }

    private fun requiredPermissions(): List<String> = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // ================= 连接 =================

    @SuppressLint("MissingPermission")
    override fun connect(listener: HeartRateSource.Listener) {
        this.listener = listener
        validator.reset()
        unitVerifier.reset()
        lastLoggedUnit = null
        lastHeartRate = 0

        val avail = isAvailable()
        if (!avail.available) {
            listener.onError(avail.message ?: "BLE 不可用", fatal = true)
            return
        }

        val sc = adapter?.bluetoothLeScanner
        if (sc == null) {
            listener.onError("无法获取 BLE 扫描器", fatal = true)
            return
        }
        scanner = sc

        listener.onSearching()
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val sc = scanner ?: return

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_HR))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            sc.startScan(filters, settings, scanCallback)
            scanning = true
            Log.d(TAG, "startScan, preferred=$preferredName")

            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "startScan failed", t)
            listener?.onError("BLE 扫描启动失败：${t.message}", fatal = true)
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            listener?.onError(
                "BLE 搜索超时（${SCAN_TIMEOUT_MS / 1000} 秒未发现心率带）。" +
                    "请确认胸带已佩戴、电极打湿、且未被其他 App 占用。",
                fatal = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacks(scanTimeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Throwable) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val name = r.device?.name
            val advName = r.scanRecord?.deviceName
            val shown = name ?: advName

            Log.d(TAG, "found ${r.device?.address} name=$shown rssi=${r.rssi}")

            // 名字过滤：优先匹配 preferredName，未配置则取第一个
            if (preferredName != null && !preferredName.isBlank()) {
                val target = (shown ?: "").lowercase()
                if (!target.contains(preferredName.lowercase())) {
                    // 不是目标设备，继续扫（但记录名字，方便用户排查）
                    targetName = targetName ?: shown
                    return
                }
            }

            stopScan()
            targetName = shown
            listener?.onConnected(shown)
            connectGatt(r.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(scanTimeout)
            Log.e(TAG, "onScanFailed code=$errorCode")
            listener?.onError("BLE 扫描失败（错误码 $errorCode）", fatal = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: android.bluetooth.BluetoothDevice) {
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt failed", t)
            listener?.onError("BLE 连接失败：${t.message}", fatal = false)
        }
    }

    private val connectTimeout = Runnable {
        if (!connected) {
            listener?.onError("BLE 连接超时", fatal = false)
            disconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeout)
                    connected = true

                    // 请求高优先级连接：缩短连接间隔，减少帧在设备端排队的时间。
                    // 心率数据量很小，但默认间隔（30-50ms）会让多拍 RR 挤在同一包里，
                    // 降低时间分辨率。HIGH 档可压到 11.25-15ms。
                    try {
                        g?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        Log.d(TAG, "requestConnectionPriority(HIGH)")
                    } catch (t: Throwable) {
                        Log.w(TAG, "requestConnectionPriority failed: ${t.message}")
                    }

                    // 必须发现服务后才能拿 characteristic
                    g?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    mainHandler.removeCallbacks(connectTimeout)
                    diagnoseDisconnect(status)
                    listener?.onDisconnected()
                }
            }
        }

        /** GATT 133 之类的高频错误，给出可操作提示而不是干巴巴的错误码。 */
        private fun diagnoseDisconnect(status: Int) {
            when (status) {
                BluetoothGatt.GATT_FAILURE ->
                    Log.w(TAG, "GATT_FAILURE(133) 常见原因：胸带被其他 App 占用 / 需要重新配对")
                else -> Log.d(TAG, "disconnect status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("发现 GATT 服务失败（status=$status）", fatal = false)
                return
            }
            val service = g?.getService(SERVICE_HR)
            if (service == null) {
                listener?.onError("设备没有心率服务（0x180D），不像心率带", fatal = false)
                return
            }
            val ch = service.getCharacteristic(CHAR_HR_MEASUREMENT)
            if (ch == null) {
                listener?.onError("设备没有心率测量特征（0x2A37）", fatal = false)
                return
            }
            enableNotification(g, ch)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotification(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            try {
                g.setCharacteristicNotification(ch, true)

                val cccd = ch.getDescriptor(DESC_CCCD)
                if (cccd == null) {
                    listener?.onError("特征缺少 CCCD 描述符，无法订阅通知", fatal = false)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "enableNotification failed", t)
                listener?.onError("订阅心率通知失败：${t.message}", fatal = false)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (descriptor?.uuid == DESC_CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "notification enabled, waiting for RR data")
                } else {
                    listener?.onError("通知订阅被拒绝（status=$status）", fatal = false)
                }
            }
        }

        /** API 33 之前走这个两参重载。 */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value
            if (v != null) handleValue(v)
        }

        /** API 33+ 走带 value 的三参重载。 */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleValue(value)
        }
    }

    /** 一帧 0x2A37 的落地处理。回调线程 → 只做计算，不做 IO。 */
    private fun handleValue(value: ByteArray) {
        val l = listener ?: return
        val frame = BleRrParser.parse(value)
        if (frame == null) {
            l.onDropped(1, "unparsable frame")
            return
        }

        if (frame.heartRate in 1..255) lastHeartRate = frame.heartRate

        if (frame.rrTicks.isEmpty()) {
            // 有些胸带在首帧/低功耗时只发心率不发 RR，不算丢弃
            return
        }

        val hrForCheck = if (frame.heartRate in 1..255) frame.heartRate else lastHeartRate

        for (raw in frame.rrTicks) {
            // 1) 用「RR 反推心率 vs 广播心率」判定设备用的哪种单位
            unitVerifier.feed(raw, hrForCheck)

            // 2) 统一折算到标准 tick（1/1024s），下游只认这一种单位
            val ticks = when (unitVerifier.decidedUnit()) {
                RrUnitVerifier.Unit.MILLISECOND ->
                    ((raw.toLong() * BleRrParser.TICKS_PER_SECOND + 500) / 1000).toInt()
                RrUnitVerifier.Unit.TICK, RrUnitVerifier.Unit.UNDECIDED -> raw
            }

            when (val r = validator.validate(ticks)) {
                is BleBeatValidator.Result.Valid -> {
                    l.onValidRr(
                        ticks,
                        com.rrlogger.ant.RrCalculator.sanitizeHeartRate(hrForCheck)
                    )
                }
                is BleBeatValidator.Result.OutOfRange -> {
                    l.onDropped(1, "out of range ${r.rrMs}ms")
                }
            }
        }

        // 判定结果变化时打一行日志，方便用户核对
        val unit = unitVerifier.decidedUnit()
        if (unit != lastLoggedUnit) {
            lastLoggedUnit = unit
            Log.i(TAG, "RR 单位判定 = $unit (${unitVerifier.diagnostic()})")
        }
    }

    /** 供 UI 显示当前判定到的单位。 */
    override fun diagnostic(): String = unitVerifier.diagnostic()

    // ================= 断开 =================

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(connectTimeout)
        stopScan()
        try {
            gatt?.let {
                it.disconnect()
                it.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "close gatt failed", t)
        } finally {
            gatt = null
            connected = false
            validator.reset()
        }
    }

    override fun release() {
        disconnect()
        listener = null
    }
}
