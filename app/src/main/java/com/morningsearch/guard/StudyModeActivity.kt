package com.morningsearch.guard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** A non-dismissible progress screen; the session ends automatically at its planned time. */
class StudyModeActivity : Activity() {
    private lateinit var countdown: TextView
    private lateinit var detail: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            val timer = FocusTimer(this@StudyModeActivity)
            val remaining = timer.remainingMs()
            if (remaining <= 0L) {
                StudyModeManager(this@StudyModeActivity).reconcile()
                finish()
                return
            }
            countdown.text = formatDuration(remaining)
            detail.text = "Focus started\nEnds: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timer.endTime()))}\n\nAllowed apps cannot be changed until this study session finishes."
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (28 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding); setBackgroundColor(Color.rgb(20, 43, 34))
        }
        content.addView(TextView(this).apply { text = "Study Session Active"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        countdown = TextView(this).apply { textSize = 50f; setTextColor(Color.rgb(210, 245, 203)); gravity = Gravity.CENTER; setPadding(0, padding, 0, padding) }
        detail = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        content.addView(countdown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(detail)
        setContentView(content)
    }

    override fun onResume() { super.onResume(); handler.post(update) }
    override fun onPause() { handler.removeCallbacks(update); super.onPause() }
    override fun onBackPressed() { moveTaskToBack(true) }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1_000L
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }
}
