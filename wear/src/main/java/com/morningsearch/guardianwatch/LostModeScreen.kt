package com.morningsearch.guardianwatch

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LostModeScreen : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(30, 5, 5))
        }
        content.addView(text("LOST MODE", 22f))
        status = text("", 14f)
        content.addView(status, matchWrap())
        content.addView(button("Enable Lost Mode") { confirm("Enable Lost Mode?") { send("lost_mode_on", "This phone is protected by Guardian Lock. Please contact the owner.") } }, matchWrap())
        content.addView(button("Siren") { send("alarm_on") }, matchWrap())
        content.addView(button("Flashlight") { send("flashlight_on") }, matchWrap())
        content.addView(button("Locate") { send("locate_now") }, matchWrap())
        content.addView(button("Found Device") { confirm("Clear Lost Mode?") { send("found_device") } }, matchWrap())
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Thread {
            val item = runCatching { WatchStatusRepository(this).refresh() }.getOrElse { WatchStateStore(this).lastStatus() }
            runOnUiThread {
                status.text = "Phone: ${if (item.phoneOnline) "Online" else "Offline"}\nBattery: ${item.batteryPercent}%\nLast location: ${item.location.ifBlank { "--" }}\nLast seen: ${item.capturedAt}"
            }
        }.start()
    }

    private fun send(command: String, payload: String = "") {
        Thread {
            val result = runCatching { WatchCommandManager(this).send(command, payload) }.getOrElse { "Failed: ${it.message}" }
            runOnUiThread {
                status.text = result
                refresh()
            }
        }.start()
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> action() }.show()
    }

    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { action() } }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
