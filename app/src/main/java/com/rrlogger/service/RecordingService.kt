package com.rrlogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rrlogger.R
import com.rrlogger.source.HeartRateManager
import com.rrlogger.source.HeartRateSource
import com.rrlogger.data.AppDatabase
import com.rrlogger.data.Label
import com.rrlogger.data.RrRecord
import com.rrlogger.data.RrRepository
import com.rrlogger.ui.MainActivity
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
        private const val CHANNEL_ID = "rr_recording"
        private const val CHANNEL_NAME = "心率记录"
        private const val NOTIF_ID = 4101

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
        val diag: String = ""
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this

        val db = AppDatabase.get(this)
        repo = RrRepository(db.sessionDao(), db.rrRecordDao())
        hr = HeartRateManager(this)

        createChannel()
        observeAnt()

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
                startForegroundSafely(buildNotification(recording = _uiState.value.recording))
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
            startForegroundSafely(buildNotification(recording = true))
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

            startForegroundSafely(buildNotification(recording = false))
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
        startCollecting(sessionId)
        startForegroundSafely(buildNotification(recording = true))
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
            startForegroundSafely(buildNotification(recording = false))
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

                        val cur = _uiState.value
                        val total = cur.totalBeats + 1
                        val dropped = hr.stats.value.dropped.toInt()
                        // 整数四舍五入除法（hrSum/hrSamples 都是整型，避免浮点抖动）
                        val avg = if (hrSamples > 0) {
                            ((hrSum + hrSamples / 2L) / hrSamples.toLong()).toInt()
                        } else 0

                        _uiState.value = cur.copy(
                            totalBeats = total,
                            droppedCount = dropped,
                            lastRrMs = beat.rrMs,
                            lastHr = beat.heartRate,
                            avgHr = avg
                        )

                        // 通知内容实时更新（节流：每 5 拍刷一次）
                        if (total % 5 == 0) {
                            updateNotification()
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

    private fun observeAnt() {
        stateJob = scope.launch {
            hr.connState.collect { st ->
                _uiState.value = _uiState.value.copy(
                    connState = st,
                    antMessage = (st as? HeartRateManager.ConnState.Error)?.reason
                )
                updateNotification()
            }
        }
        scope.launch {
            hr.stats.collect { s ->
                val cur = _uiState.value
                if (cur.droppedCount != s.dropped.toInt() || cur.rawRr != s.rawRr) {
                    _uiState.value = cur.copy(
                        rawRr = s.rawRr,
                        droppedCount = s.dropped.toInt(),
                        diag = hr.diagnostic()
                    )
                }
            }
        }
    }

    private fun emitEvent(msg: String) {
        _events.tryEmit(ToastEvent(msg))
    }

    // ---------------- 通知与前台 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "R-R 间期长期记录"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(recording: Boolean): Notification {
        val s = _uiState.value
        val text = if (recording) {
            val hrText = if (s.lastHr > 0) "${s.lastHr} bpm" else "-- bpm"
            "正在记录 · 心率 $hrText · 已记录 ${s.totalBeats} 拍"
        } else {
            when (val c = s.connState) {
                is HeartRateManager.ConnState.Connected -> "设备已连接，等待开始记录"
                is HeartRateManager.ConnState.Searching -> "正在搜索心率带…"
                is HeartRateManager.ConnState.Error -> "${s.sourceKind.label} 异常：${c.reason}"
                else -> "未在记录"
            }
        }

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rr)
            .setContentTitle(if (recording) "R-R 记录中" else "R-R Logger")
            .setContentText(text)
            .setOngoing(recording)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (recording) {
            builder.addAction(R.drawable.ic_stat_rr, "结束记录", stopPi)
        }
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ID, buildNotification(_uiState.value.recording))
        } catch (_: Throwable) {
            // 通知权限被拒时静默
        }
    }

    private fun startForegroundSafely(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            // 例如用户拒绝通知权限时 API 34 会抛异常，降级为普通前台
            try {
                startForeground(NOTIF_ID, n)
            } catch (_: Throwable) {
                emitEvent("前台服务启动失败：${t.message}")
            }
        }
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
