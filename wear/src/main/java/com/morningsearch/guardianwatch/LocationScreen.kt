package com.morningsearch.guardianwatch

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class LocationScreen : Activity() {
    private lateinit var details: TextView
    private lateinit var store: WatchStateStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WatchStateStore(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(5, 8, 7))
        }
        content.addView(text("Location", 22f))
        details = text("", 14f)
        content.addView(details, matchWrap())
        content.addView(button("Locate Now") { locateNow() }, matchWrap())
        content.addView(button("Refresh") { refresh() }, matchWrap())
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun locateNow() {
        Thread {
            val result = runCatching { WatchCommandManager(this).send("locate_now") }.getOrElse { "Failed: ${it.message}" }
            Thread.sleep(1200)
            runOnUiThread {
                details.text = result
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        Thread {
            val status = runCatching { WatchStatusRepository(this).refresh() }.getOrElse { store.lastStatus() }
            runOnUiThread { render(status) }
        }.start()
    }

    private fun render(status: WatchStatus) {
        val parts = status.location.split("@").map { it.trim() }
        val coords = parts.firstOrNull().orEmpty().split(",")
        val lat = coords.getOrNull(0)?.trim().orEmpty()
        val lon = coords.getOrNull(1)?.trim().orEmpty()
        val fixTime = parts.getOrNull(1)?.toLongOrNull()
        val age = fixTime?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) }
        details.text = buildString {
            append("Latitude: ${lat.ifBlank { "--" }}")
            append("\nLongitude: ${lon.ifBlank { "--" }}")
            append("\nAccuracy: --")
            append("\nLast update: ${fixTime?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "--"}")
            append("\nLocation age: ${age?.let { "${it}s" } ?: "--"}")
            append("\nTransport: ${status.transport}")
        }
    }

    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { action() } }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
