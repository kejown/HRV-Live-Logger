package com.rrlogger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rrlogger.source.HeartRateManager
import com.rrlogger.source.HeartRateSource
import com.rrlogger.data.Label
import com.rrlogger.data.Session
import com.rrlogger.R
import com.rrlogger.service.RecordingService
import com.rrlogger.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /** 上一次渲染的心率/拍数，用于判断是否需要触发动画与曲线推送。 */
    private var lastRenderedHr = 0
    private var lastRenderedBeats = 0
    private var lastRenderedSessionId: Long? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 权限结果不影响主流程；通知被拒时前台服务仍能跑（仅无横幅）
        refreshBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()
        setupButtons()
        observe()
    }

    // ---------------- 权限 ----------------

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            // API 34 起 FOREGROUND_SERVICE_TYPE_HEALTH 强制要求 BODY_SENSORS
            needed += Manifest.permission.BODY_SENSORS
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                needed += Manifest.permission.BODY_SENSORS
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------------- 交互 ----------------

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (!requireAntReady()) return@setOnClickListener
            vm.start(Label.REST)
            vm.connectSensor()
        }

        binding.btnStop.setOnClickListener {
            vm.stop()
        }

        binding.btnMark.setOnClickListener {
            showLabelPicker()
        }

        binding.btnConnect.setOnClickListener {
            vm.connectSensor()
        }

        binding.btnExport.setOnClickListener {
            vm.exportLatestFinished()
        }

        binding.btnRefreshAnt.setOnClickListener {
            vm.checkAnt()
            refreshBanner()
        }

        binding.btnHistory.setOnClickListener {
            showHistory()
        }

        binding.btnProtocol.setOnClickListener {
            showProtocolPicker()
        }
    }

    /** ANT+ 不可用时禁用开始并给出明确提示，绝不崩（硬约束 5）。 */
    private fun requireAntReady(): Boolean {
        val avail = vm.uiState.value.availability
        if (avail?.available == true) return true
        AlertDialog.Builder(this)
            .setTitle("ANT+ 不可用")
            .setMessage(avail?.message ?: "未检测到 ANT+ 环境")
            .setPositiveButton("知道了", null)
            .show()
        return false
    }

    /**
     * 协议切换。默认 BLE（几乎所有手机都能用），
     * 有 ANT 硬件的设备可切到 ANT+ 获得设备的丢拍计数器。
     */
    private fun showProtocolPicker() {
        val current = vm.uiState.value.serviceState.sourceKind
        val kinds = HeartRateSource.SourceKind.entries.toTypedArray()
        val labels = kinds.map { k ->
            if (k == current) "${k.label}（当前）" else k.label
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择连接协议")
            .setItems(labels) { _, which ->
                val target = kinds[which]
                if (target == current) return@setItems
                if (vm.uiState.value.serviceState.recording) {
                    AlertDialog.Builder(this)
                        .setTitle("正在记录")
                        .setMessage("切换协议会中断当前连接。建议先结束记录，切换后重新开始。")
                        .setPositiveButton("仍要切换") { _, _ -> vm.switchProtocol(target) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    vm.switchProtocol(target)
                }
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun showLabelPicker() {
        val labels = Label.PRESETS.dropLast(1) + "自定义…"
        AlertDialog.Builder(this)
            .setTitle("选择打点标签")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.lastIndex) {
                    promptCustomLabel()
                } else {
                    vm.mark(labels[which])
                }
            }
            .show()
    }

    private fun promptCustomLabel() {
        val input = android.widget.EditText(this).apply {
            hint = "例如 WARMUP / RECOVERY / 5K"
        }
        AlertDialog.Builder(this)
            .setTitle("自定义标签")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) vm.mark(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistory() {
        val sessions = vm.uiState.value.sessions
        if (sessions.isEmpty()) {
            toast("还没有任何会话")
            return
        }
        val items = sessions.map { s -> describe(s) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("会话列表（点选导出）")
            .setItems(items) { _, which ->
                vm.exportSession(sessions[which])
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun describe(s: Session): String {
        val start = dateFmt.format(Date(s.startTime))
        val dur = if (s.endTime != null) {
            val sec = (s.endTime - s.startTime) / 1000
            "${sec / 60}分${sec % 60}秒"
        } else "进行中"
        return "#${s.id}  $start  ${s.label}  ${s.totalBeats}拍  " +
            "均${s.avgHr}bpm  丢${s.droppedCount}  $dur"
    }

    // ---------------- 观察状态 ----------------

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.uiState.collect { st ->
                        render(st)
                    }
                }
                launch {
                    vm.toasts.collect { msg -> toast(msg) }
                }
            }
        }
        // 时长每秒刷新
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderDuration()
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun render(st: MainViewModel.ScreenState) {
        val s = st.serviceState

        // 会话切换时清空曲线，避免上一段的波形残留
        if (lastRenderedSessionId != s.sessionId) {
            lastRenderedSessionId = s.sessionId
            binding.sparkline.clear()
            lastRenderedBeats = 0
        }

        // 无数据时用单个长横线，比 "--" 在超大字号下更干净
        val hrText = if (s.lastHr > 0) s.lastHr.toString() else "\u2014"
        binding.tvHr.text = hrText

        // 心率变化时做一次轻微脉冲，让界面有"在跳"的感觉
        if (s.lastHr > 0 && s.lastHr != lastRenderedHr) {
            lastRenderedHr = s.lastHr
            pulseHeartRate()
            binding.tvHr.setTextColor(heartRateColor(s.lastHr))
            binding.sparkline.setIntensityColor(s.lastHr)
        } else if (s.lastHr <= 0) {
            lastRenderedHr = 0
            binding.tvHr.setTextColor(ContextCompat.getColor(this, com.rrlogger.R.color.dim))
            binding.sparkline.setIntensityColor(0)
        }

        binding.tvRr.text = if (s.lastRrMs > 0) "${s.lastRrMs} ms" else "-- ms"

        // 新拍到达 → 推一个点到曲线
        if (s.totalBeats > lastRenderedBeats && s.lastRrMs > 0) {
            val delta = s.totalBeats - lastRenderedBeats
            lastRenderedBeats = s.totalBeats
            if (delta == 1) {
                binding.sparkline.push(s.lastRrMs)
            } else {
                // 一次跳多拍（后台回到前台），只补最后一个点，避免曲线突变
                binding.sparkline.push(s.lastRrMs)
            }
        }

        binding.tvStatus.text = when {
            s.recording -> "进行中 · ${s.label}"
            s.sessionId != null -> "已结束 · ${s.label}"
            else -> "未开始"
        }
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (s.recording) com.rrlogger.R.color.ok else com.rrlogger.R.color.dim
            )
        )

        binding.tvBeats.text = s.totalBeats.toString()
        binding.tvDropped.text = s.droppedCount.toString()
        binding.tvAvgHr.text = if (s.avgHr > 0) "均 ${s.avgHr}" else getString(R.string.avg_placeholder)

        binding.btnStart.isEnabled = st.canStart
        binding.btnStop.isEnabled = st.canStop

        // 记录中把「结束记录」提到视觉上层：危险色描边 + 文字变色
        val errColor = ContextCompat.getColor(this, R.color.err)
        val dimColor = ContextCompat.getColor(this, R.color.dim)
        if (s.recording) {
            binding.btnStop.strokeColor = android.content.res.ColorStateList.valueOf(errColor)
            binding.btnStop.setTextColor(errColor)
        } else {
            binding.btnStop.strokeColor =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.stroke))
            binding.btnStop.setTextColor(dimColor)
        }
        binding.btnMark.isEnabled = s.recording
        binding.btnExport.isEnabled = !st.exporting && st.sessions.isNotEmpty()
        binding.btnExport.text = if (st.exporting) "导出中" else getString(R.string.action_export_short)
        binding.btnProtocol.text = s.sourceKind.label

        renderQuality(s)
        renderConn(s.connState, st.availability?.message)
        renderRecent(st)
        renderDuration()
    }

    @SuppressLint("SetTextI18n")
    private fun renderConn(conn: HeartRateManager.ConnState, antMessage: String?) {
        val (text, color) = when (conn) {
            is HeartRateManager.ConnState.Connected -> {
                "已连接${conn.deviceName?.let { " · $it" } ?: ""}" to com.rrlogger.R.color.ok
            }
            is HeartRateManager.ConnState.Searching -> "搜索心率带中…" to com.rrlogger.R.color.warn
            is HeartRateManager.ConnState.Requesting -> "正在申请连接…" to com.rrlogger.R.color.warn
            is HeartRateManager.ConnState.Disconnected -> "已断开" to com.rrlogger.R.color.err
            is HeartRateManager.ConnState.Error -> "错误：${conn.reason}" to com.rrlogger.R.color.err
            else -> "未连接" to com.rrlogger.R.color.dim
        }
        binding.tvConn.text = text
        binding.tvConn.setTextColor(ContextCompat.getColor(this, color))
        tintDot(binding.dotStatus, color)

        val diag = vm.uiState.value.serviceState.diag
        binding.tvDiag.text = diag
        binding.tvDiag.visibility = if (diag.isBlank()) View.GONE else View.VISIBLE

        val msg = antMessage
        binding.tvAntBanner.text = msg.orEmpty()
        binding.tvAntBanner.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * 数据质量：用丢弃率给一个可操作的判断。
     * 生理过滤丢得多通常意味着电极接触不良或剧烈运动伪影，
     * 这比单纯堆一个数字对用户更有用。
     */
    private fun renderQuality(s: RecordingService.UiState) {
        // 分级规则在 QualityAdvisor 里，由单测锁定边界；这里只做文案与配色。
        val verdict = QualityAdvisor.evaluate(s.droppedCount, s.rawRr)
        if (verdict.level == QualityAdvisor.Level.IDLE) {
            binding.tvQuality.text = getString(R.string.quality_idle)
            binding.tvQuality.setTextColor(ContextCompat.getColor(this, R.color.dim))
            tintDot(binding.dotQuality, R.color.dim)
            return
        }

        val pct = String.format(Locale.US, "%.1f%%", verdict.droppedRate * 100)

        val (text, colorRes) = when (verdict.level) {
            QualityAdvisor.Level.EXCELLENT -> "数据质量优秀 · 丢弃率 $pct" to R.color.ok
            QualityAdvisor.Level.GOOD -> "数据质量良好 · 丢弃率 $pct" to R.color.ok
            QualityAdvisor.Level.FAIR -> "有少量异常 · 丢弃率 $pct，留意电极接触" to R.color.warn
            // IDLE 已在上面提前返回；保留分支只为穷尽 when。
            QualityAdvisor.Level.POOR, QualityAdvisor.Level.IDLE ->
                "异常较多 · 丢弃率 $pct，建议重新佩戴胸带" to R.color.err
        }
        binding.tvQuality.text = text
        binding.tvQuality.setTextColor(ContextCompat.getColor(this, colorRes))
        tintDot(binding.dotQuality, colorRes)
    }

    private fun tintDot(v: View, colorRes: Int) {
        (v.background?.mutate())?.setTint(ContextCompat.getColor(this, colorRes))
    }

    private fun renderRecent(st: MainViewModel.ScreenState) {
        val rows = st.recentRr
        if (rows.isEmpty()) {
            binding.tvRecent.text = "暂无数据"
            return
        }
        // 最近 5 条，最新的在上
        binding.tvRecent.text = rows.joinToString("\n") { r ->
            val t = timeFmt.format(Date(r.timestampMs))
            "$t   RR ${r.rrMs} ms   HR ${r.heartRate} bpm"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderDuration() {
        val s = vm.uiState.value.serviceState
        if (!s.recording || s.sessionStartMs <= 0L) {
            if (!s.recording) binding.tvDuration.text = "--:--:--"
            return
        }
        val sec = max(0L, (System.currentTimeMillis() - s.sessionStartMs) / 1000)
        binding.tvDuration.text = String.format(
            Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60
        )
    }

    private fun refreshBanner() {
        render(vm.uiState.value)
    }

    /** 一次轻微的缩放脉冲，跟随心率刷新节奏。 */
    private fun pulseHeartRate() {
        val v = binding.tvHr
        v.animate().cancel()
        v.animate()
            .scaleX(1.055f).scaleY(1.055f)
            .setDuration(85)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            .start()
    }

    /** 心率区间配色：低心率偏冷，高心率偏暖。 */
    private fun heartRateColor(bpm: Int): Int {
        val hex = when {
            bpm < 60 -> "#38BDF8"
            bpm < 100 -> "#FF4D5E"
            bpm < 140 -> "#FBBF24"
            else -> "#FF6B6B"
        }
        return android.graphics.Color.parseColor(hex)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
