package com.morningsearch.guardianwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var commandText: TextView
    private lateinit var store: WatchStateStore
    private lateinit var manager: WatchCommandManager
    private val eventHandler = Handler(Looper.getMainLooper())
    private var eventPollBusy = false
    private val eventPoller = object : Runnable {
        override fun run() {
            checkLatestEvent()
            eventHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WatchStateStore(this)
        manager = WatchCommandManager(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        renderStatus(store.lastStatus())
        refreshStatus()
        eventHandler.removeCallbacks(eventPoller)
        eventHandler.post(eventPoller)
    }

    override fun onPause() {
        eventHandler.removeCallbacks(eventPoller)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(5, 8, 7))
        }
        content.addView(title("Guardian Lock"))
        statusText = body("Loading phone status...")
        commandText = body("")
        content.addView(statusText, matchWrap())
        content.addView(commandText, matchWrap())
        content.addView(body("QUICK URGE"), matchWrap())
        content.addView(button("30 MIN") { confirm("Start a 30-minute Manual Urge? Browser, hotspot, and Settings will be restricted after confirmation.") { send("urge_start", "30") } }, matchWrap())
        content.addView(button("1 HR") { confirm("Start a 1-hour Manual Urge?") { send("urge_start", "60") } }, matchWrap())
        content.addView(button("2 HR") { confirm("Start a 2-hour Manual Urge?") { send("urge_start", "120") } }, matchWrap())
        content.addView(button("3 HR") { confirm("Start a 3-hour Manual Urge?") { send("urge_start", "180") } }, matchWrap())
        content.addView(button("Locate Phone") { send("locate_now") }, matchWrap())
        content.addView(button("Lock Phone") { confirm("Lock phone?") { send("lock") } }, matchWrap())
        content.addView(button("Siren") { send("alarm_on") }, matchWrap())
        content.addView(button("Flashlight") { send("flashlight_on") }, matchWrap())
        content.addView(button("CCTV Monitor") { confirm("Start visible CCTV Monitor Mode on phone?") { send("cctv_on") } }, matchWrap())
        content.addView(button("Stop CCTV") { send("cctv_off") }, matchWrap())
        content.addView(button("Lost Mode") { confirm("Enable Lost Mode?") { send("lost_mode_on", "This phone is protected by Guardian Lock. Please contact the owner.") } }, matchWrap())
        content.addView(button("Found My Phone") { confirm("Clear Lost Mode?") { send("found_device") } }, matchWrap())
        content.addView(button("Location Screen") { startActivity(Intent(this, LocationScreen::class.java)) }, matchWrap())
        content.addView(button("Lost Mode Screen") { startActivity(Intent(this, LostModeScreen::class.java)) }, matchWrap())
        content.addView(button("Pair Nearby Phone") { pairNearbyPhone() }, matchWrap())
        content.addView(button("Settings / Pairing") { showSettings() }, matchWrap())
        return ScrollView(this).apply { addView(content) }
    }

    private fun refreshStatus() {
        Thread {
            val status = runCatching { WatchStatusRepository(this).refresh() }.getOrElse { store.lastStatus().copy(transport = "Offline") }
            runOnUiThread { renderStatus(status) }
        }.start()
    }

    private fun renderStatus(status: WatchStatus) {
        val time = if (status.capturedAt > 0L) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.capturedAt)) else "--"
        statusText.text = buildString {
            append(if (status.phoneOnline) "Phone Online" else "Phone Offline")
            append("\nBattery: ${if (status.batteryPercent >= 0) "${status.batteryPercent}%" else "--"}")
            append(if (status.charging) " charging" else "")
            append("\nNetwork: ${status.network}")
            append("\nLost Mode: ${if (status.lostMode) "ON" else "off"}")
            append("\nStudy: ${if (status.studyMode) "active" else "ready"}")
            append("\nMaintenance: ${if (status.maintenanceMode) "active" else "off"}")
            append("\nCCTV: ${if (status.cctvMonitor) "active" else "off"}")
            append("\nLast sync: $time")
            append("\nTransport: ${status.transport}")
        }
    }

    private fun send(command: String, payload: String = "") {
        Thread {
            val result = runCatching { manager.send(command, payload) }.getOrElse { "Failed: ${it.message}" }
            runOnUiThread {
                commandText.text = result
                refreshStatus()
            }
        }.start()
    }

    private fun showSettings() {
        val url = EditText(this).apply { hint = "https://your-dashboard"; setText(store.cloudUrl) }
        val token = EditText(this).apply { hint = "Recovery token"; setText(store.cloudToken) }
        val key = TextView(this).apply {
            text = "Save cloud URL/token, then this watch will generate a 7-digit pairing code for the phone.\n\nFallback public key:\n${manager.publicKeyBase64()}"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(url); addView(token); addView(key) }
        AlertDialog.Builder(this)
            .setTitle("Guardian Watch Setup")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                store.cloudUrl = url.text.toString()
                store.cloudToken = token.text.toString()
                generatePairingCode()
            }.show()
    }

    private fun pairNearbyPhone() {
        Thread {
            val sent = WatchDataLayerClient(this).requestNearbyPairing(manager.publicKeyBase64())
            runOnUiThread {
                commandText.text = if (sent) {
                    "Pairing request sent to nearby phone.\nOpen Guardian Lock and approve the pending watch pairing."
                } else {
                    "No nearby phone found. Keep Bluetooth/Wear OS connection active."
                }
            }
        }.start()
    }

    private fun generatePairingCode() {
        Thread {
            val code = (1_000_000 + SecureRandom().nextInt(9_000_000)).toString()
            val result = runCatching {
                val expiresAt = WatchCloudClient(this, store).registerPairingCode(code, manager.publicKeyBase64())
                "Watch pairing code:\n$code\n\nValid until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(expiresAt))}."
            }.getOrElse {
                "Pairing code failed: ${it.message}\nCheck Cloud URL/token. Fallback: paste public key manually if needed."
            }
            runOnUiThread { commandText.text = result }
        }.start()
    }

    private fun checkLatestEvent() {
        if (eventPollBusy || store.cloudUrl.isBlank()) return
        eventPollBusy = true
        Thread {
            val event = runCatching { WatchCloudClient(this, store).latestEvent() }.getOrNull()
            if (event != null && event.id.isNotBlank() && event.id != store.lastEventId) {
                store.lastEventId = event.id
                if (event.type == "motion_detected") {
                    runOnUiThread {
                        commandText.text = "Motion detected\n${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp))}\n${event.detail}"
                        vibrateAlert()
                    }
                }
            }
            eventPollBusy = false
        }.start()
    }

    private fun vibrateAlert() {
        runCatching {
            val vibrator = getSystemService(Vibrator::class.java)
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 180), -1))
        }
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> action() }.show()
    }

    private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
    private fun body(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setTextColor(Color.rgb(220, 235, 225)); gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
