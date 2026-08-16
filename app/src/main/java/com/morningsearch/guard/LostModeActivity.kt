package com.morningsearch.guard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class LostModeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!GuardStore(this@LostModeActivity).lostModeActive) {
                finish()
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(buildScreen())
        enterKiosk()
    }

    override fun onResume() {
        super.onResume()
        if (!GuardStore(this).lostModeActive) finish()
        enterKiosk()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onBackPressed() {
        enterKiosk()
    }

    private fun buildScreen(): LinearLayout {
        val store = GuardStore(this)
        val padding = (28 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(115, 20, 20))
            addView(TextView(this@LostModeActivity).apply {
                text = "Lost Mode Active"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@LostModeActivity).apply {
                text = store.lostModeMessage.ifBlank { "This phone is protected by Guardian Lock. Please contact the owner." }
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, padding, 0, padding)
            })
            addView(Button(this@LostModeActivity).apply {
                text = "Emergency Phone"
                setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL)) }
            })
            addView(Button(this@LostModeActivity).apply {
                text = "Guardian Unlock"
                setOnClickListener { showGuardianUnlock() }
            })
        }
    }

    private fun enterKiosk() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        runCatching { startLockTask() }
    }

    private fun showGuardianUnlock() {
        val pin = EditText(this).apply {
            hint = "Guardian PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Found My Device")
            .setView(pin)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                val ok = GuardianPinStore(this).verify(pin.text.toString().toCharArray())
                pin.text.clear()
                if (ok) {
                    GuardianRecoverManager(this).foundDevice()
                    runCatching { stopLockTask() }
                    finish()
                } else {
                    enterKiosk()
                }
            }
            .show()
    }
}
