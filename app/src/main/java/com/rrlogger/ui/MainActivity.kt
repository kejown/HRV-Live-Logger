package com.rrlogger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rrlogger.R
import com.rrlogger.data.Label
import com.rrlogger.data.Session
import com.rrlogger.databinding.ActivityMainBinding
import com.rrlogger.service.RecordingService
import com.rrlogger.source.HeartRateManager
import com.rrlogger.source.HeartRateSource
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private lateinit var prefs: DisplayPrefs

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /** 上一次渲染的心率/拍数，用于判断是否需要触发动画与曲线推送。 */
    private var lastRenderedHr = 0
    private var lastRenderedBeats = 0
    private var lastRenderedRr = 0
    private var lastRenderedSessionId: Long? = null

    /** 专注模式与"最近拍数"展开状态。专注模式持久化，展开状态只在本次界面内有效。 */
    private var focusMode = false
    private var recentExpanded = false

    /** 数据质量呼吸灯；只在"优秀/良好"时运行，避免整屏都在闪。 */
    private var qualityPulse: ObjectAnimator? = null

    /** 启动/按钮触发的权限动作。 */
    private enum class PendingAction { CONNECT, START }

    private var pendingAction: PendingAction? = null
    private var pendingUserInitiated = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingAction
        val userInitiated = pendingUserInitiated
        pendingAction = null
        pendingUserInitiated = false

        val denied = result.filterValues { granted -> !granted }.keys
        // 权限结果回来后重算可用性：之前可能是"无权限 → 不可用"的假状态。
        vm.checkAnt()

        // 通知被拒不影响记录；连接阶段的身体传感器被拒也只影响"health 前台服务"，
        // 不该拦住"只看实时心率"。真正会挡住动作的只有蓝牙/定位（以及记录时的身体传感器）。
        val blocking = denied.filter { permission ->
            permission != Manifest.permission.POST_NOTIFICATIONS &&
                !(action == PendingAction.CONNECT && permission == Manifest.permission.BODY_SENSORS)
        }
        if (blocking.isNotEmpty()) {
            if (userInitiated) showPermissionRationale(blocking)
            refreshBanner()
            return@registerForActivityResult
        }
        if (action != null) perform(action, userInitiated)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = DisplayPrefs(this)
        focusMode = prefs.focusMode

        setupButtons()
        applyFocusMode(focusMode)
        applyRecentExpanded(false)
        observe()
        // 打开即连接：先按标准系统对话框要权限，授权后自动扫描心率带并实时显示。
        requestFor(PendingAction.CONNECT, userInitiated = false)
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时补一次渲染：流程只有状态变化才推送，避免时长/质量灯停在旧值。
        render(vm.uiState.value)
    }

    override fun onPause() {
        qualityPulse?.cancel()
        qualityPulse = null
        binding.dotQuality.alpha = 1f
        super.onPause()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (focusMode) {
            setFocusMode(false, persist = true)
            return
        }
        super.onBackPressed()
    }

    // ---------------- 权限 ----------------

    private fun requestFor(action: PendingAction, userInitiated: Boolean) {
        val missing = missingPermissions(action)
        if (missing.isEmpty()) {
            perform(action, userInitiated)
            return
        }
        pendingAction = action
        pendingUserInitiated = userInitiated
        permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * 只请求当前动作真正需要的权限：
     *  - 连接：蓝牙（12+ 为 SCAN/CONNECT，12 以下为定位）+ BODY_SENSORS（34 的 health 前台服务要用）；
     *  - 开始记录：再补通知权限。
     * 不在启动时把通知、定位、蓝牙一次性全要一遍。
     */
    private fun missingPermissions(action: PendingAction): List<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            wanted += Manifest.permission.BODY_SENSORS
        }
        if (action == PendingAction.START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun perform(action: PendingAction, userInitiated: Boolean) {
        when (action) {
            PendingAction.CONNECT -> {
                if (userInitiated && !requireSourceReady()) return
                vm.connectWhenReady()
            }
            PendingAction.START -> {
                if (!requireSourceReady()) return
                vm.start(Label.REST)
                vm.connectWhenReady()
            }
        }
    }

    private fun showPermissionRationale(denied: Collection<String>) {
        val what = when {
            denied.any { it.contains("BLUETOOTH") || it.contains("LOCATION") } -> "蓝牙"
            denied.any { it.contains("BODY_SENSORS") } -> "身体传感器"
            else -> "通知"
        }
        AlertDialog.Builder(this)
            .setTitle("需要${what}权限")
            .setMessage("没有${what}权限无法连接心率带并实时显示心率，可以到系统设置里手动开启。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 交互 ----------------

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            requestFor(PendingAction.START, userInitiated = true)
        }

        binding.btnStop.setOnClickListener { vm.stop() }
        binding.btnFocusStop.setOnClickListener { vm.stop() }

        binding.btnMark.setOnClickListener { showLabelPicker() }
        binding.btnFocusMark.setOnClickListener { showLabelPicker() }

        binding.btnConnect.setOnClickListener {
            requestFor(PendingAction.CONNECT, userInitiated = true)
        }

        binding.btnExport.setOnClickListener { vm.exportLatestFinished() }

        binding.btnRefreshAnt.setOnClickListener {
            vm.checkAnt()
            refreshBanner()
        }

        binding.btnHistory.setOnClickListener { showHistory() }

        binding.btnInfo.setOnClickListener { showInfoDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        binding.tvRecentHeader.setOnClickListener { applyRecentExpanded(!recentExpanded) }

        binding.btnExitFocus.setOnClickListener { setFocusMode(false, persist = true) }
    }

    /** 数据源不可用时给出明确提示，绝不崩（硬约束 5）。 */
    private fun requireSourceReady(): Boolean {
        val avail = vm.uiState.value.availability
        if (avail?.available == true) return true
        AlertDialog.Builder(this)
            .setTitle("数据源不可用")
            .setMessage(avail?.message ?: "未检测到可用的蓝牙 / ANT+ 环境")
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
        if (!vm.uiState.value.serviceState.recording) {
            toast("先开始记录再打点")
            return
        }
        val labels = Label.PRESETS.dropLast(1) + "自定义…"
        AlertDialog.Builder(this)
            .setTitle("选择打点标签")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.lastIndex) {
                    promptCustomLabel()
                } else {
                    markWithFeedback(labels[which])
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
                if (text.isNotEmpty()) markWithFeedback(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打点：先给触觉反馈，再交给服务（Toast 由服务统一发）。 */
    private fun markWithFeedback(label: String) {
        haptic()
        vm.mark(label)
    }

    private fun haptic() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        window.decorView.performHapticFeedback(constant)
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

    // ---------------- 设置 / 诊断 / 专注 ----------------

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rowProtocol = view.findViewById<TextView>(R.id.rowProtocol)
        val checkFocus = view.findViewById<CheckBox>(R.id.checkFocus)
        val checkKeepScreen = view.findViewById<CheckBox>(R.id.checkKeepScreen)

        rowProtocol.text = getString(
            R.string.settings_protocol,
            vm.uiState.value.serviceState.sourceKind.label
        )
        checkFocus.isChecked = focusMode
        checkKeepScreen.isChecked = prefs.keepScreenOn

        var dialog: AlertDialog? = null
        rowProtocol.setOnClickListener {
            dialog?.dismiss()
            showProtocolPicker()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.settings_done) { _, _ ->
                prefs.keepScreenOn = checkKeepScreen.isChecked
                setFocusMode(checkFocus.isChecked, persist = true)
                applyKeepScreenOn()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showInfoDialog() {
        val st = vm.uiState.value
        val s = st.serviceState
        val diag = s.diag.ifBlank { getString(R.string.diag_empty) }
        val text = buildString {
            append("协议：").append(s.sourceKind.label).append('\n')
            append("连接：").append(connText(s.connState).first).append('\n')
            append("会话：").append(
                when {
                    s.recording -> "记录中 · ${s.label}"
                    s.sessionId != null -> "已结束 · ${s.label}"
                    else -> "未开始"
                }
            ).append('\n')
            append("采样：").append(s.totalBeats).append(" 拍 · 丢弃 ").append(s.droppedCount).append('\n')
            val avail = st.availability
            if (avail != null) {
                append("数据源：").append(if (avail.available) "可用" else "不可用").append('\n')
            }
            append('\n').append(diag)
            if (!avail?.message.isNullOrBlank()) {
                append("\n\n").append(avail?.message)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_title)
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 专注模式：隐藏标准界面，只留心率、时长与打点。 */
    private fun setFocusMode(enabled: Boolean, persist: Boolean) {
        focusMode = enabled
        if (persist) prefs.focusMode = enabled
        applyFocusMode(enabled)
        render(vm.uiState.value)
    }

    private fun applyFocusMode(enabled: Boolean) {
        binding.mainScroll.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.focusRoot.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun applyRecentExpanded(expanded: Boolean) {
        recentExpanded = expanded
        binding.tvRecentHeader.text =
            getString(if (expanded) R.string.recent_collapse else R.string.recent_expand)
        binding.tvRecent.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun applyKeepScreenOn(recording: Boolean = vm.uiState.value.serviceState.recording) {
        if (prefs.keepScreenOn && recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------- 观察状态 ----------------

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.uiState.collect { st -> render(st) }
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
            lastRenderedRr = 0
        }

        // 无数据时用单个长横线，比 "--" 在超大字号下更干净
        val hrText = if (s.lastHr > 0) s.lastHr.toString() else "\u2014"
        binding.tvHr.text = hrText
        setFocusHr(hrText)

        // 心率变化时做一次轻微脉冲，让界面有"在跳"的感觉
        if (s.lastHr > 0 && s.lastHr != lastRenderedHr) {
            lastRenderedHr = s.lastHr
            pulseHeartRate(binding.tvHr)
            pulseHeartRate(binding.tvFocusHr)
            val color = heartRateColor(s.lastHr)
            binding.tvHr.setTextColor(color)
            binding.tvFocusHr.setTextColor(color)
            binding.sparkline.setIntensityColor(s.lastHr)
        } else if (s.lastHr <= 0) {
            lastRenderedHr = 0
            val dim = ContextCompat.getColor(this, R.color.dim)
            binding.tvHr.setTextColor(dim)
            binding.tvFocusHr.setTextColor(dim)
            binding.sparkline.setIntensityColor(0)
        }

        binding.tvRr.text = if (s.lastRrMs > 0) "${s.lastRrMs} ms" else getString(R.string.rr_placeholder)

        // 新拍到达 → 推一个点到曲线。
        // 记录中按拍数判断；未记录时的"实时监测"没有会话拍数，则按 RR 值变化判断。
        val beatAdvanced = if (s.recording) {
            s.totalBeats > lastRenderedBeats
        } else {
            s.lastRrMs > 0 && s.lastRrMs != lastRenderedRr
        }
        if (beatAdvanced && s.lastRrMs > 0) {
            lastRenderedBeats = s.totalBeats
            lastRenderedRr = s.lastRrMs
            binding.sparkline.push(s.lastRrMs)
        }

        binding.tvStatus.text = when {
            s.recording -> "进行中 · ${s.label}"
            s.sessionId != null && s.connState is HeartRateManager.ConnState.Connected ->
                "已结束 · ${s.label}"
            s.connState is HeartRateManager.ConnState.Connected -> "监测中 · 未记录"
            else -> "未开始"
        }
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (s.recording) R.color.ok else R.color.dim)
        )

        binding.tvMetrics.text = getString(R.string.metrics_format, s.totalBeats, s.droppedCount)
        binding.tvAvgHr.text =
            if (s.avgHr > 0) "均 ${s.avgHr}" else getString(R.string.avg_placeholder)
        binding.tvFocusAvg.text = binding.tvAvgHr.text

        binding.btnStart.isEnabled = st.canStart
        binding.btnStop.isEnabled = st.canStop
        binding.btnFocusStop.isEnabled = st.canStop

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
        binding.btnFocusMark.isEnabled = s.recording
        binding.btnExport.isEnabled = !st.exporting && st.sessions.isNotEmpty()
        binding.btnExport.text = if (st.exporting) "导出中" else getString(R.string.action_export_short)

        renderQuality(s)
        renderConn(s.connState, st.availability?.message)
        renderRecent(st)
        renderDuration()
        applyKeepScreenOn(s.recording)
    }

    @SuppressLint("SetTextI18n")
    private fun renderConn(conn: HeartRateManager.ConnState, antMessage: String?) {
        val (text, color) = connText(conn)
        binding.tvConn.text = text
        binding.tvConn.setTextColor(ContextCompat.getColor(this, color))
        tintDot(binding.dotStatus, color)

        val msg = antMessage
        binding.tvAntBanner.text = msg.orEmpty()
        binding.tvAntBanner.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun connText(conn: HeartRateManager.ConnState): Pair<String, Int> = when (conn) {
        is HeartRateManager.ConnState.Connected -> {
            "已连接${conn.deviceName?.let { " · $it" } ?: ""}" to R.color.ok
        }
        is HeartRateManager.ConnState.Searching -> "搜索心率带中…" to R.color.warn
        is HeartRateManager.ConnState.Requesting -> "正在申请连接…" to R.color.warn
        is HeartRateManager.ConnState.Disconnected -> "已断开" to R.color.err
        is HeartRateManager.ConnState.Error -> "错误：${conn.reason}" to R.color.err
        else -> "未连接" to R.color.dim
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
            updateQualityPulse(active = false)
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

        val healthy = verdict.level == QualityAdvisor.Level.EXCELLENT ||
            verdict.level == QualityAdvisor.Level.GOOD
        updateQualityPulse(active = healthy && s.recording)
    }

    /** 绿色呼吸灯：只在数据质量健康且正在记录时运行；其余状态保持常亮。 */
    private fun updateQualityPulse(active: Boolean) {
        if (active) {
            val animator = qualityPulse ?: ObjectAnimator.ofFloat(binding.dotQuality, "alpha", 1f, 0.35f)
                .apply {
                    duration = 900
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
                .also { qualityPulse = it }
            if (!animator.isRunning) animator.start()
        } else {
            qualityPulse?.cancel()
            qualityPulse = null
            binding.dotQuality.alpha = 1f
        }
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
        // 最新在上
        binding.tvRecent.text = rows.joinToString("\n") { r ->
            val t = timeFmt.format(Date(r.timestampMs))
            "$t   RR ${r.rrMs} ms   HR ${r.heartRate} bpm"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderDuration() {
        val s = vm.uiState.value.serviceState
        if (!s.recording || s.sessionStartMs <= 0L) {
            binding.tvDuration.text = "--:--:--"
            binding.tvFocusDuration.text = "--:--:--"
            return
        }
        val sec = max(0L, (System.currentTimeMillis() - s.sessionStartMs) / 1000)
        val text = String.format(
            Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60
        )
        binding.tvDuration.text = text
        binding.tvFocusDuration.text = text
    }

    private fun refreshBanner() {
        render(vm.uiState.value)
    }

    /** 专注模式心率：按位数换字号，避免 3 位数被裁掉、2 位数又显得不够大。 */
    private fun setFocusHr(text: String) {
        binding.tvFocusHr.text = text
        val sizeSp = if (text.length >= 3) 112f else 150f
        binding.tvFocusHr.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /** 一次轻微的缩放脉冲，跟随心率刷新节奏。 */
    private fun pulseHeartRate(v: View) {
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
