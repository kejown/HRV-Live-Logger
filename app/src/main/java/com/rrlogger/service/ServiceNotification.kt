package com.rrlogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rrlogger.R
import com.rrlogger.source.HeartRateManager
import com.rrlogger.source.HeartRateSource
import com.rrlogger.ui.MainActivity

/**
 * 前台服务通知：通道、文案、PendingIntent 与 startForeground。
 *
 * 从 [RecordingService] 里拆出来，服务本身只保留会话、采集与写库职责。
 * 文案与 v4 完全一致，只把"取当前状态"改成由调用方传入 [State]。
 */
class ServiceNotification(
    private val service: Service,
    /** 前台服务启动失败（例如通知权限被拒）时回报给服务的错误通道。 */
    private val onForegroundFailure: (String) -> Unit
) {

    /** 通知里需要的服务状态快照。 */
    data class State(
        val recording: Boolean,
        val lastHr: Int,
        val totalBeats: Int,
        val connState: HeartRateManager.ConnState,
        val sourceKind: HeartRateSource.SourceKind
    )

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = service.getSystemService(NotificationManager::class.java) ?: return
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

    /** 就地更新通知内容；通知权限被拒时静默。 */
    fun update(state: State) {
        val nm = service.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ID, build(state))
        } catch (_: Throwable) {
            // 通知权限被拒时静默
        }
    }

    /**
     * 进入前台。API 34 用 health 类型；失败时（常见于通知权限被拒）降级为普通前台，
     * 再失败才把原因回报给服务。
     */
    fun startForeground(state: State) {
        val n = build(state)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    service,
                    NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    service,
                    NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                legacyStartForeground(n)
            }
        } catch (t: Throwable) {
            try {
                legacyStartForeground(n)
            } catch (_: Throwable) {
                onForegroundFailure("前台服务启动失败：${t.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStartForeground(n: Notification) {
        service.startForeground(NOTIF_ID, n)
    }

    private fun build(state: State): Notification {
        val text = if (state.recording) {
            val hrText = if (state.lastHr > 0) "${state.lastHr} bpm" else "-- bpm"
            "正在记录 · 心率 $hrText · 已记录 ${state.totalBeats} 拍"
        } else {
            when (val c = state.connState) {
                is HeartRateManager.ConnState.Connected -> "设备已连接，等待开始记录"
                is HeartRateManager.ConnState.Searching -> "正在搜索心率带…"
                is HeartRateManager.ConnState.Error -> "${state.sourceKind.label} 异常：${c.reason}"
                else -> "未在记录"
            }
        }

        val contentPi = PendingIntent.getActivity(
            service, 0,
            Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPi = PendingIntent.getService(
            service, 1,
            Intent(service, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rr)
            .setContentTitle(if (state.recording) "HRV 记录中" else "HRV Live Logger")
            .setContentText(text)
            .setOngoing(state.recording)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.recording) {
            builder.addAction(R.drawable.ic_stat_rr, "结束记录", stopPi)
        }
        return builder.build()
    }

    private companion object {
        const val CHANNEL_ID = "rr_recording"
        const val CHANNEL_NAME = "HRV 记录"
        const val NOTIF_ID = 4101
    }
}
