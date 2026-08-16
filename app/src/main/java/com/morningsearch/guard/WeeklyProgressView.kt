package com.morningsearch.guard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar

class WeeklyProgressView(context: Context) : View(context) {
    private val bars = IntArray(7)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setEvents(timestamps: List<Long>) {
        bars.fill(0)
        val start = startOfToday() - 6L * DAY_MS
        timestamps.forEach { time ->
            val index = ((time - start) / DAY_MS).toInt()
            if (index in bars.indices) bars[index]++
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (150 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val max = bars.maxOrNull()?.coerceAtLeast(1) ?: 1
        val gap = width / 28f
        val barWidth = (width - gap * 8) / 7f
        paint.color = Color.rgb(49, 92, 70)
        bars.forEachIndexed { index, value ->
            val left = gap + index * (barWidth + gap)
            val top = height - (value.toFloat() / max) * (height * .75f) - 24f
            canvas.drawRoundRect(left, top, left + barWidth, height - 24f, 8f, 8f, paint)
            paint.textSize = 22f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(value.toString(), left + barWidth / 2, top - 5f, paint)
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object { private const val DAY_MS = 86_400_000L }
}
