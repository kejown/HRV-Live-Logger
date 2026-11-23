package com.rrlogger.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.rrlogger.source.HeartRateManager
import com.rrlogger.source.HeartRateSource
import com.rrlogger.data.AppDatabase
import com.rrlogger.data.Label
import com.rrlogger.data.RrRecord
import com.rrlogger.data.RrRepository
import com.rrlogger.hrv.HrvCalculator
import com.rrlogger.hrv.HrvMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers


/**
 * 记录用的前台服务。
 *
 * - 持有 [HeartRateManager]，把每条有效 RR 立即写 Room（硬约束 4：不攒批）
 * - 通知栏实时显示：正在记录 · 心率 XX bpm · 已记录 N 拍
 * - START_STICKY：被杀后重启并从 Room 恢复未结束会话（硬约束 8）
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"

        /** 实时 HRV 的滑动窗口长度。 */
        private const val LIVE_WINDOW_MS = 60_000L

        const val ACTION_START = "com.rrlogger.action.START"
        const val ACTION_STOP = "com.rrlogger.action.STOP"

        /**
         * 进程级引用，供 ViewModel 在 bind 回调未到时兜底。
         * 用弱引用避免静态字段长期持有 Service（Lint: StaticFieldLeak）。
         */
        private var sInstanceRef: java.lang.ref.WeakReference<RecordingService>? = null

        var instance: RecordingService?
            get() = sInstanceRef?.get()
            private set(value) {
                sInstanceRef = value?.let { java.lang.ref.WeakReference(it) }
            }

        fun start(context: Context) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    // ---------------- 对外状态（UI 只读） ----------------

    data class UiState(
        val sessionId: Long? = null,
        val recording: Boolean = false,
        val label: String = Label.REST,
        val totalBeats: Int = 0,
        val droppedCount: Int = 0,
        val sessionStartMs: Long = 0L,
        val lastRrMs: Int = 0,
        val lastHr: Int = 0,
        val avgHr: Int = 0,
        val connState: HeartRateManager.ConnState = HeartRateManager.ConnState.Idle,
        val sourceKind: HeartRateSource.SourceKind = HeartRateSource.SourceKind.BLE,
        val antMessage: String? = null,
        /** 收到的原始 RR 条数（含被丢弃）。 */
        val rawRr: Long = 0,

        /** 数据源诊断串（BLE 下含 RR 单位判定）。 */
        val diag: String = "",

        /** 最近 60 秒滑窗的 HRV；不可用时为 null。 */
        val hrv: HrvMetrics? = null
    ) {
        val elapsedMs: Long get() = if (sessionStartMs > 0 && recording)
            System.currentTimeMillis() - sessionStartMs else 0L
    }

    data class ToastEvent(val message: String)

    inner class LocalBinder : Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var repo: RrRepository
    private lateinit var hr: HeartRateManager
    private lateinit var notifier: ServiceNotification

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ToastEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ToastEvent> = _events.asSharedFlow()

    private val writeMutex = Mutex()
    private var collectJob: Job? = null
    private var stateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 心率累加（用 Long 防溢出），与总拍数一起算均值。 */
    private var hrSum: Long = 0L
    private var hrSamples: Int = 0

    /** 实时 HRV 的滑动窗口：最近 60 秒的有效拍。 */
    private val liveBeats = ArrayDeque<LiveBeat>()

    private data class LiveBeat(val timestampMs: Long, val rrMs: Int)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this

        val db = AppDatabase.get(this)
        repo = RrRepository(db.sessionDao(), db.rrRecordDao())
        hr = HeartRateManager(this)
        notifier = ServiceNotification(this) { msg -> emitEvent(msg) }

        notifier.createChannel()
        observeAnt()

        // 未记录时也把实时心率推给界面：只想"打开看一眼心率"的人不必先开记录。
        // 记录期间由 startCollecting 负责推送，这里用 recording 判断避免两边打架。
        // 同一路数据同时维护 60 秒 HRV 滑窗，界面上的 HRV 卡片与是否记录无关。
        scope.launch {
            hr.beats.collect { beat ->
                _uiState.update { cur ->
                    if (cur.recording) cur
                    else cur.copy(lastRrMs = beat.rrMs, lastHr = beat.heartRate)
                }
                appendLiveBeat(beat.timestampMs, beat.rrMs)
                recomputeLiveHrv()
            }
        }

        scope.launch {
            // 冷启动即恢复：如果有未结束会话，直接接着写
            val unfinished = repo.findUnfinishedSession()
            if (unfinished != null) {
                resumeSession(unfinished.id, unfinished.label, unfinished.startTime)
                emitEvent("已恢复未结束的会话 #${unfinished.id}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { stopSession() }
            }
            else -> {
                notifier.startForeground(notificationState())
                scope.launch { ensureRestoredOrIdle() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try {
            collectJob?.cancel()
            stateJob?.cancel()
            hr.release()
            releaseWakeLock()
            scope.cancel()
        } finally {
            super.onDestroy()
        }
    }

    // ---------------- 会话控制（Binder 暴露给 UI） ----------------

    /** 开始一个新的记录会话。若已在进行中则忽略。 */
    fun startSession(label: String = Label.REST) {
        scope.launch {
            if (_uiState.value.recording) {
                emitEvent("已经在记录中")
                return@launch
            }
            val startMs = System.currentTimeMillis()
            val id = repo.createSession(label, startMs)
            lockAndResume(id, label, startMs)
            acquireWakeLock()
            notifier.startForeground(notificationState())
            emitEvent("开始记录（#${id} · $label）")
        }
    }

    /** 结束当前会话：落库 endTime / totalBeats / avgHr / droppedCount。 */
    fun stopSession() {
        scope.launch {
            val s = _uiState.value
            val id = s.sessionId
            if (id == null || !s.recording) {
                emitEvent("当前没有进行中的会话")
                return@launch
            }

            // 保证最后一批已入队样本先落库
            writeMutex.withLock { /* 等待在途写入完成 */ }

            val avg = if (hrSamples > 0) (hrSum / hrSamples).toInt() else 0
            repo.finishSession(
                id = id,
                endTime = System.currentTimeMillis(),
                totalBeats = s.totalBeats,
                avgHr = avg,
                dropped = s.droppedCount
            )

            collectJob?.cancel()
            releaseWakeLock()
            _uiState.value = _uiState.value.copy(
                recording = false,
                avgHr = avg,
                lastRrMs = 0,
                lastHr = 0
            )
            hrSum = 0
            hrSamples = 0

            notifier.startForeground(notificationState())
            emitEvent("会话已结束：${s.totalBeats} 拍，平均 $avg bpm")
        }
    }

    /** 会话进行中打标签：改 Session.label，并让本会话后续导出文件名带上它。 */
    fun markLabel(label: String) {
        scope.launch {
            val s = _uiState.value
            val id = s.sessionId
            if (id == null || !s.recording) {
                emitEvent("先开始记录再打点")
                return@launch
            }
            repo.updateLabel(id, label)
            _uiState.value = _uiState.value.copy(label = label)
            emitEvent("已打点：$label")
        }
    }

    fun heartRateManager(): HeartRateManager = hr

    fun connectSensor() {
        hr.checkAvailability()
        hr.connect()
    }

    /** 切换协议（ANT+ / BLE），会断开当前连接并重置统计。 */
    fun switchProtocol(kind: HeartRateSource.SourceKind) {
        val avail = hr.switchSource(kind)
        _uiState.value = _uiState.value.copy(
            sourceKind = kind,
            antMessage = avail.message,
            connState = if (avail.available) {
                HeartRateManager.ConnState.Idle
            } else {
                HeartRateManager.ConnState.Error(avail.message ?: "不可用", fatal = true)
            }
        )
        emitEvent(
            if (avail.available) "已切换到 ${kind.label} 模式"
            else "${kind.label} 不可用：${avail.message}"
        )
        resetLiveWindow()
    }

    // ---------------- 内部实现 ----------------

    private fun lockAndResume(sessionId: Long, label: String, startMs: Long) {
        _uiState.value = _uiState.value.copy(
            sessionId = sessionId,
            recording = true,
            label = label,
            sessionStartMs = startMs,
            totalBeats = 0,
            droppedCount = 0,
            avgHr = 0,
            lastRrMs = 0,
            lastHr = 0
        )
        hrSum = 0
        hrSamples = 0
        resetLiveWindow()
        startCollecting(sessionId)
        if (hr.connState.value !is HeartRateManager.ConnState.Connected) {
            hr.connect()
        }
    }

    /** 服务重启后接着写同一个 sessionId，不新建（硬约束 8）。 */
    private suspend fun resumeSession(sessionId: Long, label: String, startMs: Long) {
        val existing = repo.countRr(sessionId)
        val session = repo.getSession(sessionId)
        _uiState.value = _uiState.value.copy(
            sessionId = sessionId,
            recording = true,
            label = label,
            sessionStartMs = startMs,
            totalBeats = existing,
            droppedCount = session?.droppedCount ?: 0,
            avgHr = session?.avgHr ?: 0
        )
        hrSum = (session?.avgHr?.toLong() ?: 0L) * existing
        hrSamples = existing
        resetLiveWindow()
        startCollecting(sessionId)
        notifier.startForeground(notificationState())
        acquireWakeLock()
        if (hr.connState.value !is HeartRateManager.ConnState.Connected) {
            hr.connect()
        }
    }

    private suspend fun ensureRestoredOrIdle() {
        val ui = _uiState.value
        if (ui.recording) return
        val unfinished = repo.findUnfinishedSession()
        if (unfinished != null) {
            resumeSession(unfinished.id, unfinished.label, unfinished.startTime)
        } else {
            notifier.startForeground(notificationState())
        }
    }

    private fun startCollecting(sessionId: Long) {
        collectJob?.cancel()
        collectJob = scope.launch {
            hr.beatStream.collect { beat ->
                // 每条有效 RR 立即落库，不攒批（硬约束 4）
                writeMutex.withLock {
                    try {
                        repo.insertRr(
                            RrRecord(
                                sessionId = sessionId,
                                timestampMs = beat.timestampMs,
                                rrMs = beat.rrMs,
                                heartRate = beat.heartRate
                            )
                        )
                        hrSum += beat.heartRate.toLong()
                        hrSamples += 1

                        val dropped = hr.stats.value.dropped.toInt()
                        // 整数四舍五入除法（hrSum/hrSamples 都是整型，避免浮点抖动）
                        val avg = if (hrSamples > 0) {
                            ((hrSum + hrSamples / 2L) / hrSamples.toLong()).toInt()
                        } else 0

                        var total = 0
                        _uiState.update { cur ->
                            total = cur.totalBeats + 1
                            cur.copy(
                                totalBeats = total,
                                droppedCount = dropped,
                                lastRrMs = beat.rrMs,
                                lastHr = beat.heartRate,
                                avgHr = avg
                            )
                        }

                        // 通知内容实时更新（节流：每 5 拍刷一次）
                        if (total % 5 == 0) {
                            notifier.update(notificationState())
                        }
                        // 计数定期落库，防进程被杀丢统计（数据本身已逐条落库）
                        if (total % 20 == 0) {
                            repo.updateStats(sessionId, total, avg, dropped)
                        }
                    } catch (t: Throwable) {
                        emitEvent("写入失败：${t.message}")
                    }
                }
            }
        }
    }

    // ---------------- 实时 HRV 滑窗 ----------------

    private fun appendLiveBeat(timestampMs: Long, rrMs: Int) {
        if (rrMs <= 0) return
        liveBeats.addLast(LiveBeat(timestampMs, rrMs))
        val cutoff = timestampMs - LIVE_WINDOW_MS
        while (liveBeats.isNotEmpty() && liveBeats.first().timestampMs < cutoff) {
            liveBeats.removeFirst()
        }
    }

    private fun recomputeLiveHrv() {
        if (liveBeats.size < 2) return
        val rr = ArrayList<Int>(liveBeats.size)
        for (b in liveBeats) rr += b.rrMs
        val metrics = HrvCalculator.compute(rr) ?: return
        _uiState.update { it.copy(hrv = metrics) }
    }

    /** 新会话/换协议时清空滑窗，避免上一段的数据混进这一段的 HRV。 */
    private fun resetLiveWindow() {
        liveBeats.clear()
        _uiState.update { it.copy(hrv = null) }
    }

    private fun observeAnt() {
        stateJob = scope.launch {
            hr.connState.collect { st ->
                _uiState.update {
                    it.copy(
                        connState = st,
                        antMessage = (st as? HeartRateManager.ConnState.Error)?.reason
                    )
                }
                notifier.update(notificationState())
            }
        }
        scope.launch {
            hr.stats.collect { s ->
                _uiState.update { cur ->
                    if (cur.droppedCount != s.dropped.toInt() || cur.rawRr != s.rawRr) {
                        cur.copy(
                            rawRr = s.rawRr,
                            droppedCount = s.dropped.toInt(),
                            diag = hr.diagnostic()
                        )
                    } else {
                        cur
                    }
                }
            }
        }
    }

    private fun emitEvent(msg: String) {
        _events.tryEmit(ToastEvent(msg))
    }

    // ---------------- 通知与前台 ----------------

    /** 把服务的 UI 状态缩成通知需要的字段，具体构建交给 [ServiceNotification]。 */
    private fun notificationState(): ServiceNotification.State {
        val s = _uiState.value
        return ServiceNotification.State(
            recording = s.recording,
            lastHr = s.lastHr,
            totalBeats = s.totalBeats,
            connState = s.connState,
            sourceKind = s.sourceKind
        )
    }

    // ---------------- WakeLock ----------------

    private fun acquireWakeLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+：health 类型前台服务本身即可持续，不需要 partial wakelock
            return
        }
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RRLogger::recording"
        ).apply {
            setReferenceCounted(false)
            try {
                acquire(12L * 60L * 60L * 1000L) // 12 小时上限
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

}
