package com.rrlogger.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.rrlogger.R
import kotlin.math.abs

/**
 * RR 间期实时曲线。
 *
 * 不做复杂图表，只画一条滚动的折线 + 渐变填充：
 *  - 直接把最近 N 拍的 RR 铺开，能一眼看出节律是否稳定
 *  - 静息时应该是几乎水平的一条线；出现尖峰说明有伪影/漏拍
 *  - 颜色跟随当前心率区间，低心率偏冷、高心率偏暖
 *
 * 性能：只保留环形缓冲，onDraw 里不做任何分配（Path 复用），
 * 每来一拍 invalidate 一次，60bpm 下也就每秒 1 次刷新。
 */
class RrSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 显示的拍数。60bpm 约等于 20 秒窗口。 */
        private const val CAPACITY = 32
    }

    /** RR 毫秒值的环形缓冲。 */
    private val values = FloatArray(CAPACITY)
    private var count = 0
    private var head = 0

    /** 平滑后的上下界，避免每次刷新都跳变。 */
    private var lo = 800f
    private var hi = 1200f

    private var lineColor = ContextCompat.getColor(context, R.color.accent)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = lineColor
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.stroke)
    }

    /** 基线（平均线）。 */
    private val meanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#33FFFFFF")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }

    private val linePath = Path()
    private val fillPath = Path()

    /** 最新点的圆点画笔：预分配，onDraw 里不再 new（Lint: DrawAllocation）。 */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = lineColor
    }

    fun push(rrMs: Int) {
        if (rrMs <= 0) return
        values[head] = rrMs.toFloat()
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++

        // 上下界平滑跟随，避免曲线因为单拍异常被压扁
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until count) {
            val v = values[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val pad = ((mx - mn) * 0.25f).coerceAtLeast(30f)
        lo = lerp(lo, mn - pad, 0.25f)
        hi = lerp(hi, mx + pad, 0.25f)

        invalidate()
    }

    /** 按心率区间调整线条颜色，视觉上呼应强度。 */
    fun setIntensityColor(bpm: Int) {
        val c = when {
            bpm <= 0 -> ContextCompat.getColor(context, R.color.dim)
            bpm < 60 -> Color.parseColor("#38BDF8")
            bpm < 100 -> ContextCompat.getColor(context, R.color.accent)
            bpm < 140 -> Color.parseColor("#FBBF24")
            else -> Color.parseColor("#FF6B6B")
        }
        if (c != lineColor) {
            lineColor = c
            linePaint.color = c
            buildFillShader()
            invalidate()
        }
    }

    fun clear() {
        count = 0
        head = 0
        lo = 800f
        hi = 1200f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildFillShader()
    }

    private fun buildFillShader() {
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat().coerceAtLeast(1f),
            intArrayOf(
                (lineColor and 0x00FFFFFF) or 0x1A000000,
                (lineColor and 0x00FFFFFF) or 0x00000000
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 两条水平参考线，给曲线一个空间参照
        val g1 = h * 0.33f
        val g2 = h * 0.66f
        canvas.drawLine(0f, g1, w, g1, gridPaint)
        canvas.drawLine(0f, g2, w, g2, gridPaint)

        if (count < 2) return

        val span = (hi - lo).coerceAtLeast(1f)
        val stepX = w / (CAPACITY - 1).toFloat()
        val startOffset = CAPACITY - count

        linePath.reset()
        fillPath.reset()

        var meanSum = 0f
        var firstX = -1f
        var prevX = 0f
        var prevY = 0f

        for (i in 0 until count) {
            val v = values[i]
            meanSum += v
            val x = (startOffset + i) * stepX
            val norm = ((v - lo) / span).coerceIn(0f, 1f)
            // y 轴翻转：RR 大（心率慢）画在上面
            val y = h - norm * h

            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
                firstX = x
            } else {
                // 用二次贝塞尔做轻微平滑，避免锯齿
                val midX = (prevX + x) / 2f
                val midY = (prevY + y) / 2f
                linePath.quadTo(prevX, prevY, midX, midY)
                fillPath.lineTo(x, y)
            }
            prevX = x
            prevY = y
        }
        // 收尾，保证线画到最后一点
        linePath.lineTo(prevX, prevY)
        fillPath.lineTo(prevX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // 平均线
        val mean = meanSum / count
        val meanNorm = ((mean - lo) / span).coerceIn(0f, 1f)
        val meanY = h - meanNorm * h
        if (firstX >= 0) {
            canvas.drawLine(firstX, meanY, prevX, meanY, meanPaint)
        }

        // 最新一点画个实心点
        val lastNorm = ((values[(head - 1 + CAPACITY) % CAPACITY]) - lo) / span
        val dotY = h - lastNorm.coerceIn(0f, 1f) * h
        dotPaint.color = lineColor
        canvas.drawCircle(prevX, dotY, dp(3f), dotPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 用于判断曲线是否明显异常（供 Activity 决定是否提示）。 */
    fun currentSpread(): Float {
        if (count < 2) return 0f
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until count) {
            val v = values[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val mean = (mn + mx) / 2f
        return if (mean > 0) abs(mx - mn) / mean else 0f
    }
}
