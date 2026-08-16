package com.morningsearch.guard

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.morningsearch.guard.data.GuardianDatabase

class UrgeDelayActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var breathing: TextView
    private lateinit var reason: TextView

    private val tick = object : Runnable {
        override fun run() {
            val remaining = (GuardStore(this@UrgeDelayActivity).pendingActivateAt - System.currentTimeMillis())
                .coerceAtLeast(0L)
            countdown.text = "${(remaining + 999L) / 1000L} seconds"
            val phase = ((System.currentTimeMillis() / 4_000L) % 4L).toInt()
            breathing.text = when (phase) {
                0 -> "Breathe in slowly"
                1 -> "Hold gently"
                2 -> "Breathe out slowly"
                else -> "Pause"
            }
            if (remaining == 0L) {
                LockManager(this@UrgeDelayActivity).reconcile()
                finish()
            } else handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20, 45, 35)
        val pad = (28 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(20, 45, 35))
        }
        root.addView(TextView(this).apply {
            text = "Let the urge pass"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        countdown = lightText(42f)
        breathing = lightText(22f)
        reason = lightText(18f).apply { text = "You chose recovery for a reason." }
        root.addView(countdown)
        root.addView(breathing)
        root.addView(reason)
        root.addView(lightText(15f).apply {
            text = "The lock will activate automatically. Emergency and essential apps remain available."
        })
        setContentView(root)
        loadReason()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun loadReason() {
        Thread {
            val text = kotlinx.coroutines.runBlocking {
                GuardianDatabase.get(this@UrgeDelayActivity).guardianDao().enabledReasons().firstOrNull()?.text
            }
            if (!text.isNullOrBlank()) runOnUiThread { reason.text = "Your reason: $text" }
        }.start()
    }

    private fun lightText(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        val p = (12 * resources.displayMetrics.density).toInt()
        setPadding(0, p, 0, p)
    }
}
