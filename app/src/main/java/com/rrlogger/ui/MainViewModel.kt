package com.rrlogger.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rrlogger.source.HeartRateManager
import com.rrlogger.source.HeartRateSource
import com.rrlogger.data.AppDatabase
import com.rrlogger.data.Label
import com.rrlogger.data.RrRecord
import com.rrlogger.data.RrRepository
import com.rrlogger.data.Session
import com.rrlogger.export.RrExporter
import com.rrlogger.service.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI 的唯一读写入口。UI 只读 [uiState]，不碰业务逻辑。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val db = AppDatabase.get(appContext)
    private val repo = RrRepository(db.sessionDao(), db.rrRecordDao())
    private val exporter = RrExporter(appContext, repo)

    data class ScreenState(
        val serviceState: RecordingService.UiState = RecordingService.UiState(),
        val availability: HeartRateSource.Availability? = null,
        val sessions: List<Session> = emptyList(),
        val recentRr: List<RrRecord> = emptyList(),
        val exporting: Boolean = false
    ) {
        val antUsable: Boolean get() = availability?.available == true
        val canStart: Boolean
            get() = antUsable && !serviceState.recording && serviceState.sessionId == null
        val canStop: Boolean get() = serviceState.recording
    }

    private val _ui = MutableStateFlow(ScreenState())
    val uiState: StateFlow<ScreenState> = _ui.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /**
     * 同进程 bound service 的弱引用。
     * 用 WeakReference 而非强引用：ViewModel 生命周期可能长于 Service，
     * 强引用会让 Service 无法被回收（Lint: StaticFieldLeak / context leak）。
     */
    private var serviceRef: java.lang.ref.WeakReference<RecordingService>? = null
    private val service: RecordingService?
        get() = serviceRef?.get()

    private var serviceJob: Job? = null
    private var connected = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? RecordingService.LocalBinder)?.service() ?: return
            serviceRef = java.lang.ref.WeakReference(s)
            connected = true
            bindServiceFlows(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected = false
            serviceRef = null
            serviceJob?.cancel()
        }
    }

    init {
        RecordingService.start(appContext)
        appContext.bindService(
            Intent(appContext, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        // 兜底：如果 bind 回调延迟，直接用进程级单例
        RecordingService.instance?.let {
            serviceRef = java.lang.ref.WeakReference(it)
            connected = true
            bindServiceFlows(it)
        }

        viewModelScope.launch {
            repo.observeSessions().collect { list ->
                _ui.value = _ui.value.copy(sessions = list)
            }
        }
        viewModelScope.launch {
            repo.observeLatestRr(10).collect { list ->
                _ui.value = _ui.value.copy(recentRr = list)
            }
        }
        checkAnt()
    }

    private fun bindServiceFlows(s: RecordingService) {
        serviceJob?.cancel()
        serviceJob = viewModelScope.launch {
            launch {
                s.uiState.collect { st ->
                    _ui.value = _ui.value.copy(serviceState = st)
                }
            }
            launch {
                s.events.collect { e -> _toasts.tryEmit(e.message) }
            }
        }
        // 顺带把 ANT 可用性也拉一次
        _ui.value = _ui.value.copy(
            availability = s.heartRateManager().checkAvailability()
        )
    }

    fun checkAnt() {
        val svc = service
        if (svc != null) {
            _ui.value = _ui.value.copy(availability = svc.heartRateManager().checkAvailability())
        } else if (RecordingService.instance != null) {
            _ui.value = _ui.value.copy(
                availability = RecordingService.instance!!.heartRateManager().checkAvailability()
            )
        } else {
            _ui.value = _ui.value.copy(availability = probeAvailability())
        }
    }

    /**
     * 服务尚未建成时的只读探测。
     * 直接构造一个临时 HeartRateManager 问可用性 —— 不连接、不订阅，
     * 只读蓝牙/ANT 环境状态。
     */
    private fun probeAvailability(): HeartRateSource.Availability {
        val probe = HeartRateManager(appContext)
        val kind = _ui.value.serviceState.sourceKind
        val avail = try {
            probe.switchSource(kind)
        } catch (_: Throwable) {
            HeartRateSource.Availability(false, "探测失败", "")
        } finally {
            try {
                probe.release()
            } catch (_: Throwable) {
            }
        }
        return avail
    }

    fun connectSensor() {
        val svc = service ?: RecordingService.instance ?: return
        svc.heartRateManager().checkAvailability()
        svc.heartRateManager().connect()
    }

    /** 切换协议：ANT+ / BLE。 */
    fun switchProtocol(kind: HeartRateSource.SourceKind) {
        val svc = service ?: RecordingService.instance
        if (svc == null) {
            _toasts.tryEmit("服务尚未就绪")
            return
        }
        svc.switchProtocol(kind)
        _ui.value = _ui.value.copy(availability = svc.heartRateManager().checkAvailability())
    }

    fun start(label: String = Label.REST) {
        val svc = service ?: RecordingService.instance
        if (svc == null) {
            _toasts.tryEmit("服务尚未就绪，请稍候")
            return
        }
        svc.startSession(label)
    }

    fun stop() {
        (service ?: RecordingService.instance)?.stopSession()
    }

    fun mark(label: String) {
        (service ?: RecordingService.instance)?.markLabel(label)
    }

    fun exportSession(session: Session) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(exporting = true)
            val r = exporter.export(session.id, session.label, session.startTime)
            _ui.value = _ui.value.copy(exporting = false)
            _toasts.tryEmit(r.message)
        }
    }

    /** 导出最近一次已结束会话（没有则退化为最近一条）。 */
    fun exportLatestFinished() {
        val target = _ui.value.sessions.firstOrNull { it.endTime != null }
            ?: _ui.value.sessions.firstOrNull()
        if (target == null) {
            _toasts.tryEmit("还没有任何会话")
            return
        }
        exportSession(target)
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            repo.deleteSession(session.id)
            _toasts.tryEmit("已删除会话 #${session.id}")
        }
    }

    override fun onCleared() {
        try {
            if (connected) appContext.unbindService(connection)
        } catch (_: Throwable) {
        }
        connected = false
        super.onCleared()
    }
}
