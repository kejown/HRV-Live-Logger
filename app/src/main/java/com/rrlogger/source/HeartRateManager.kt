package com.rrlogger.source

import android.content.Context
import android.util.Log
import com.rrlogger.ant.RrCalculator
import com.rrlogger.ant.SdkAntHeartRateSource
import com.rrlogger.ble.BleHeartRateSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 心率连接管理器（协议无关）。
 *
 * 职责：
 *  - 持有一个 [HeartRateSource]（ANT+ 或 BLE），可运行时切换
 *  - 把 source 交上来的 rrTicks 统一换算成毫秒
 *  - 以有界 Flow 推给 RecordingService，回调线程绝不做 IO
 *
 * 所有业务状态（会话、写库、通知）都不在这里，见 RecordingService。
 */
class HeartRateManager(context: Context) {

    companion object {
        private const val TAG = "HeartRateManager"
    }

    private val appContext = context.applicationContext

    /** 当前数据源。切换前必须 [disconnect]。 */
    private var source: HeartRateSource = buildSource(HeartRateSource.SourceKind.BLE)

    private var listenerAttached = false

    // ---------------- 对外状态 ----------------

    sealed interface ConnState {
        data object Idle : ConnState
        data object Requesting : ConnState
        data object Searching : ConnState
        data class Connected(val deviceName: String?) : ConnState
        data class Error(val reason: String, val fatal: Boolean = false) : ConnState
        data object Disconnected : ConnState
    }

    data class BeatSample(
        val timestampMs: Long,
        val rrMs: Int,
        val heartRate: Int
    )

    data class Stats(
        /** 通过全部校验并入库的拍数。 */
        val validBeats: Long = 0,
        /** 被丢弃的条数。含义随协议不同，见 README「两种协议的 droppedCount 差异」。 */
        val dropped: Long = 0,
        /** 收到的原始 RR 条数（含被丢弃的）。 */
        val rawRr: Long = 0
    )

    private val _connState = MutableStateFlow<ConnState>(ConnState.Idle)
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val _availability = MutableStateFlow<HeartRateSource.Availability?>(null)
    val availability: StateFlow<HeartRateSource.Availability?> = _availability.asStateFlow()

    private val _activeKind = MutableStateFlow(source.kind)
    val activeKind: StateFlow<HeartRateSource.SourceKind> = _activeKind.asStateFlow()

    /** 热流，容量有界 + DROP_OLDEST，绝不阻塞协议回调线程。 */
    private val _beats = MutableSharedFlow<BeatSample>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val beats: SharedFlow<BeatSample> = _beats.asSharedFlow()

    /** Service 主消费通道。 */
    private val beatChannel = Channel<BeatSample>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val beatStream: Flow<BeatSample> = beatChannel.receiveAsFlow()

    // ---------------- 数据源管理 ----------------

    private fun buildSource(kind: HeartRateSource.SourceKind): HeartRateSource = when (kind) {
        HeartRateSource.SourceKind.ANT_PLUS -> SdkAntHeartRateSource(appContext)
        HeartRateSource.SourceKind.BLE -> BleHeartRateSource(appContext)
    }

    /**
     * 切换协议。会先断开当前连接并重置统计。
     * @return 切换后的可用性
     */
    fun switchSource(kind: HeartRateSource.SourceKind): HeartRateSource.Availability {
        if (kind == source.kind) return checkAvailability()
        Log.d(TAG, "switchSource ${source.kind} -> $kind")
        disconnect()
        source.release()
        listenerAttached = false
        source = buildSource(kind)
        _activeKind.value = kind
        _stats.value = Stats()
        val avail = checkAvailability()
        _connState.value = if (avail.available) {
            ConnState.Idle
        } else {
            ConnState.Error(avail.message ?: "不可用", fatal = true)
        }
        return avail
    }

    fun checkAvailability(): HeartRateSource.Availability =
        source.isAvailable().also { _availability.value = it }

    fun currentKind(): HeartRateSource.SourceKind = source.kind

    /** 当前数据源的诊断串（BLE 下含 RR 单位判定）。 */
    fun diagnostic(): String = try {
        source.diagnostic()
    } catch (_: Throwable) {
        ""
    }

    // ---------------- 连接 ----------------

    fun connect() {
        if (_connState.value is ConnState.Connected || _connState.value is ConnState.Requesting) {
            Log.d(TAG, "connect ignored, state=${_connState.value}")
            return
        }

        val avail = checkAvailability()
        if (!avail.available) {
            _connState.value = ConnState.Error(avail.message ?: "不可用", fatal = true)
            return
        }

        _connState.value = ConnState.Requesting
        _stats.value = Stats()

        source.connect(object : HeartRateSource.Listener {

            override fun onSearching() {
                if (_connState.value !is ConnState.Connected) {
                    _connState.value = ConnState.Searching
                }
            }

            override fun onConnected(deviceName: String?) {
                _connState.value = ConnState.Connected(deviceName)
            }

            override fun onValidRr(rrTicks: Int, heartRate: Int) {
                // 唯一的 tick → ms 换算点，两条协议共用，避免算法分叉
                val rrMs = RrCalculator.ticksToMs(rrTicks)
                if (!RrCalculator.isPhysiologicallyValid(rrMs)) {
                    _stats.value = _stats.value.let {
                        it.copy(dropped = it.dropped + 1, rawRr = it.rawRr + 1)
                    }
                    return
                }

                val sample = BeatSample(
                    timestampMs = System.currentTimeMillis(),
                    rrMs = rrMs,
                    heartRate = heartRate
                )
                _beats.tryEmit(sample)
                beatChannel.trySend(sample)

                _stats.value = _stats.value.let {
                    it.copy(validBeats = it.validBeats + 1, rawRr = it.rawRr + 1)
                }
            }

            override fun onDropped(count: Int, reason: String?) {
                if (reason != null) Log.v(TAG, "dropped $count: $reason")
                _stats.value = _stats.value.let {
                    it.copy(dropped = it.dropped + count, rawRr = it.rawRr + count)
                }
            }

            override fun onDisconnected() {
                if (_connState.value !is ConnState.Error) {
                    _connState.value = ConnState.Disconnected
                }
            }

            override fun onError(message: String, fatal: Boolean) {
                _connState.value = ConnState.Error(message, fatal)
            }
        })
        listenerAttached = true
    }

    fun disconnect() {
        source.disconnect()
        if (_connState.value !is ConnState.Error) {
            _connState.value = ConnState.Disconnected
        }
    }

    fun release() {
        source.release()
        listenerAttached = false
        beatChannel.close()
    }

    @Suppress("unused")
    fun isListenerAttached(): Boolean = listenerAttached
}
