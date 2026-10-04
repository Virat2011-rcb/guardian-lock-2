package com.morningsearch.guard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.morningsearch.guard.data.GuardianDatabase
import com.morningsearch.guard.data.KeywordPackImporter
import com.morningsearch.guard.data.PersonalReasonEntity
import kotlinx.coroutines.runBlocking
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var analytics: TextView
    private lateinit var graph: WeeklyProgressView
    private lateinit var setPinButton: Button
    private lateinit var urgeButton: Button
    private var localDashboardText: TextView? = null
    private var keywordImportAuthorized = false
    private var renderedDeviceOwner = false
    private val urgeTicker = Handler(Looper.getMainLooper())
    private val urgeTick = object : Runnable {
        override fun run() {
            if (renderedDeviceOwner) refreshStatus()
            urgeTicker.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        val optionalRecoverPermissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            optionalRecoverPermissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            optionalRecoverPermissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            optionalRecoverPermissions += Manifest.permission.READ_PHONE_STATE
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            optionalRecoverPermissions += Manifest.permission.CAMERA
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            optionalRecoverPermissions += Manifest.permission.RECORD_AUDIO
        }
        if (optionalRecoverPermissions.isNotEmpty()) requestPermissions(optionalRecoverPermissions.toTypedArray(), 11)
        EnforcementService.start(this)
        renderedDeviceOwner = LockManager(this).isDeviceOwner
        setContentView(if (renderedDeviceOwner) buildScreen() else buildDeviceOwnerBlocker())
    }

    override fun onResume() {
        super.onResume()
        val deviceOwner = LockManager(this).isDeviceOwner
        if (deviceOwner != renderedDeviceOwner) {
            renderedDeviceOwner = deviceOwner
            setContentView(if (deviceOwner) buildScreen() else buildDeviceOwnerBlocker())
        }
        LockManager(this).beginCommitmentIfNeeded()
        if (renderedDeviceOwner) {
            refreshStatus()
            loadAnalytics()
            urgeTicker.post(urgeTick)
            checkPendingWatchPairing()
        }
    }

    override fun onPause() {
        urgeTicker.removeCallbacks(urgeTick)
        super.onPause()
    }

    private fun buildDeviceOwnerBlocker(): ScrollView {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
            setBackgroundColor(Color.rgb(94, 18, 18))
        }
        content.addView(TextView(this).apply {
            text = "Protection Incomplete"
            textSize = 32f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Guardian Lock is installed, but Device Owner is not active. Strong browser blocking, uninstall protection, data-clear protection, and app suspension are not guaranteed until Device Owner provisioning is completed."
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, padding, 0, padding)
        })
        content.addView(TextView(this).apply {
            text = "For MIUI 12.5.5, use Android Enterprise QR provisioning from the factory-reset setup screen. Do not add Google/Xiaomi accounts before provisioning."
            textSize = 16f
            setTextColor(Color.rgb(255, 235, 180))
            setPadding(0, 0, 0, padding)
        })
        content.addView(actionButton("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap())
        content.addView(actionButton("Refresh status") {
            renderedDeviceOwner = LockManager(this).isDeviceOwner
            setContentView(if (renderedDeviceOwner) buildScreen() else buildDeviceOwnerBlocker())
        }, matchWrap())
        return ScrollView(this).apply { addView(content) }
    }

    private fun buildScreen(): LinearLayout {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 247, 244))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
            setBackgroundColor(Color.rgb(27, 54, 42))
        }
        toolbar.addView(TextView(this).apply { text = "Guardian Lock"; textSize = 28f; setTextColor(Color.WHITE) })
        toolbar.addView(TextView(this).apply { text = "Private protection dashboard"; textSize = 14f; setTextColor(Color.rgb(202, 225, 211)) })
        root.addView(toolbar)
        val pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val home = buildHomePage(padding)
        val protection = buildProtectionPage(padding)
        val urge = buildUrgePage(padding)
        val recover = buildRecoverPage(padding)
        val more = buildMorePage(padding)
        listOf(home, protection, urge, recover, more).forEach { pages.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        fun show(index: Int) { for (i in 0 until pages.childCount) pages.getChildAt(i).visibility = if (i == index) android.view.View.VISIBLE else android.view.View.GONE }
        show(0)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); setPadding(4, 4, 4, 4) }
        listOf("Home", "Protection", "Urge", "Recover", "More").forEachIndexed { index, label ->
            nav.addView(Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.rgb(27, 54, 42))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { show(index) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        return root
    }

    private fun scrollPage(padding: Int): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) })
    }

    private fun pageContent(page: ScrollView): LinearLayout = page.getChildAt(0) as LinearLayout

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 20f; setTextColor(Color.rgb(27, 54, 42)); setPadding(0, 8, 0, 10)
    }

    private fun card(title: String, description: String = ""): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 14, 18, 14)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 22f; setStroke(1, Color.rgb(222, 229, 223)) }
        addView(TextView(this@MainActivity).apply { text = title; textSize = 17f; setTextColor(Color.rgb(27, 54, 42)) })
        if (description.isNotBlank()) addView(TextView(this@MainActivity).apply { text = description; textSize = 13f; setTextColor(Color.rgb(84, 96, 88)); setPadding(0, 4, 0, 8) })
    }

    private fun addCard(content: LinearLayout, card: LinearLayout) { content.addView(card, matchWrap().apply { setMargins(0, 0, 0, 12) }) }

    private fun buildHomePage(padding: Int): ScrollView {
        val page = scrollPage(padding); val content = pageContent(page)
        status = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(55, 67, 59)) }
        val protection = card("PROTECTION", "Your local enforcement and connection status")
        protection.addView(status)
        addCard(content, protection)
        val urgeCard = card("URGE", "Need a stronger shield? Manual URGE blocks browsers and applies the configured strong lock.")
        urgeButton = actionButton("ACTIVATE URGE") { showUrgeDurationDialog() }
        urgeButton.setTextColor(Color.WHITE); urgeButton.setBackgroundColor(Color.rgb(140, 45, 55)); urgeButton.minHeight = 54
        urgeCard.addView(urgeButton, matchWrap()); addCard(content, urgeCard)
        val today = card("TODAY", "Quick view of current restrictions")
        today.addView(TextView(this).apply { text = "Study Mode and daily app limits are shown in Protection. CCTV and recovery controls are in Recover."; textSize = 14f })
        addCard(content, today)
        val prediction = card("PREDICTED URGE PROTECTION", "Automatic browser-only protection based on your recent pattern")
        prediction.addView(TextView(this).apply { text = UrgePredictionManager(this@MainActivity).description(); textSize = 14f; setTextColor(Color.rgb(104, 78, 30)) })
        addCard(content, prediction)
        val quick = card("QUICK STATUS")
        quick.addView(TextView(this).apply { text = "Hotspot: ${if (GuardStore(this@MainActivity).urgeActive) "RESTRICTED" else "AVAILABLE"}\nSettings: ${if (GuardStore(this@MainActivity).urgeActive) "RESTRICTED" else "AVAILABLE"}\nTransport: ${GuardStore(this@MainActivity).cloudTransportState.uppercase()}\nEmergency calling and essential safety apps remain available."; textSize = 14f })
        addCard(content, quick)
        return page
    }

    private fun buildProtectionPage(padding: Int): ScrollView {
        val page = scrollPage(padding); val content = pageContent(page); content.addView(heading("Protection"))
        val study = card("STUDY MODE", "Focus session with a frozen app allow-list")
        study.addView(actionButton(if (GuardStore(this).studyModeActive) "Study Mode Active" else "Start Study Mode") { showStudyModeDialog() }, matchWrap()); addCard(content, study)
        val limits = card("DAILY APP LIMITS", "Time limits are enforced locally and remain PIN-protected")
        limits.addView(TextView(this).apply { text = AppLimitManager(this@MainActivity).summary().ifBlank { "No daily limits configured yet." }; textSize = 14f }); limits.addView(actionButton("Configure limits") { requireGuardianAuth("Daily app time limit") { showAppLimitSettings() } }, matchWrap()); addCard(content, limits)
        val predicted = card("PREDICTED URGE PROTECTION", "AUTOMATIC · BROWSER ONLY")
        predicted.addView(TextView(this).apply { text = UrgePredictionManager(this@MainActivity).description() + "\nHotspot: AVAILABLE · Settings: AVAILABLE"; textSize = 14f }); addCard(content, predicted)
        val active = card("WHAT IS BLOCKED NOW?", "A transparent explanation of active restrictions")
        active.addView(TextView(this).apply { text = "Browser protection follows current lock state. Manual URGE may restrict hotspot and Settings; predicted protection is browser-only."; textSize = 14f }); addCard(content, active)
        val rules = card("GUARDIAN RULES", "Current rule ownership")
        rules.addView(TextView(this).apply {
            val store = GuardStore(this@MainActivity)
            text = "Study Mode: ${if (store.studyModeActive) "ON" else "READY"}\nBrowser: ${if (store.isLocked || UrgePredictionManager(this@MainActivity).prediction()?.isActiveNow() == true) "BLOCKED" else "AVAILABLE"}\nHotspot: ${if (store.urgeActive) "RESTRICTED · Manual URGE" else "AVAILABLE · No Manual URGE"}\nSettings: ${if (store.urgeActive) "RESTRICTED · Manual URGE" else "AVAILABLE · No Manual URGE"}"
            textSize = 14f
        }); addCard(content, rules)
        return page
    }

    private fun buildUrgePage(padding: Int): ScrollView {
        val page = scrollPage(padding); val content = pageContent(page); content.addView(heading("Urge"))
        val shield = card("YOUR MANUAL PROTECTION SHIELD", "Use this when you need deliberate extra distance from browsing triggers.")
        shield.addView(actionButton("ACTIVATE URGE") { showUrgeDurationDialog() }, matchWrap()); shield.addView(actionButton("View 7-day history") { showUrgeHistory() }, matchWrap()); addCard(content, shield)
        analytics = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(55, 67, 59)) }
        val insights = card("7-DAY INSIGHTS"); insights.addView(analytics); addCard(content, insights)
        graph = WeeklyProgressView(this); val chart = card("PATTERN OVERVIEW", "Recent trigger activity, stored locally"); chart.addView(graph, matchWrap()); addCard(content, chart)
        return page
    }

    private fun buildRecoverPage(padding: Int): ScrollView {
        val page = scrollPage(padding); val content = pageContent(page); content.addView(heading("Recover"))
        val device = card("DEVICE", "Guardian-authenticated recovery actions")
        device.addView(actionButton("Locate Now") { requireGuardianAuth("Locate device") { val snapshot = GuardianRecoverManager(this).refreshStatus(); status.append("\nLocation: ${snapshot.locationSummary}"); loadAnalytics() } }, matchWrap())
        device.addView(actionButton("Lock Now") { requireGuardianAuth("Lock device") { showRecoverResult(GuardianRecoverManager(this).lockDevice(true)); refreshStatus() } }, matchWrap())
        device.addView(actionButton("Lost Mode") { requireGuardianAuth("Enable Lost Mode") { showLostModeDialog() } }, matchWrap()); addCard(content, device)
        val alerts = card("ALERTS")
        alerts.addView(actionButton("Start Siren") { requireGuardianAuth("Start siren") { showRecoverResult(GuardianRecoverManager(this).startAlarm()); refreshStatus() } }, matchWrap())
        alerts.addView(actionButton("Stop Siren") { requireGuardianAuth("Stop siren") { showRecoverResult(GuardianRecoverManager(this).stopAlarm()); refreshStatus() } }, matchWrap())
        alerts.addView(actionButton("Flashlight Blink") { requireGuardianAuth("Flashlight blink") { showRecoverResult(GuardianRecoverManager(this).startFlashlight()); refreshStatus() } }, matchWrap())
        alerts.addView(actionButton("Stop Flashlight") { requireGuardianAuth("Stop flashlight") { showRecoverResult(GuardianRecoverManager(this).stopFlashlight()); refreshStatus() } }, matchWrap()); addCard(content, alerts)
        val camera = card("CAMERA & CCTV", "Visible, user-consented recovery capture only")
        camera.addView(actionButton("Front Camera Photo") { requireGuardianAuth("Front camera photo") { showRecoverResult(GuardianRecoverManager(this).requestCapture("capture_front")); loadAnalytics() } }, matchWrap())
        camera.addView(actionButton("Rear Camera Photo") { requireGuardianAuth("Rear camera photo") { showRecoverResult(GuardianRecoverManager(this).requestCapture("capture_rear")); loadAnalytics() } }, matchWrap())
        camera.addView(actionButton("Record Audio") { requireGuardianAuth("Record audio") { showRecoverResult(GuardianRecoverManager(this).requestCapture("record_audio")); loadAnalytics() } }, matchWrap())
        camera.addView(actionButton(if (GuardStore(this).cctvMonitorActive) "CCTV Monitor Active" else "Start CCTV Monitor") { requireGuardianAuth("CCTV Monitor Mode") { showRecoverResult(GuardianRecoverManager(this).startCctvMonitor()); refreshStatus() } }, matchWrap()); addCard(content, camera)
        return page
    }

    private fun buildMorePage(padding: Int): ScrollView {
        val page = scrollPage(padding); val content = pageContent(page); content.addView(heading("More"))
        val watch = card("GUARDIAN WATCH", "Nearby and cloud pairing status")
        watch.addView(TextView(this).apply { text = if (GuardStore(this@MainActivity).watchPublicKeyBase64.isNotBlank()) "CONNECTED / PAIRED" else "NOT PAIRED"; textSize = 14f })
        watch.addView(actionButton("Pair Watch") { requireGuardianAuth("Pair Guardian Watch") { showWatchPairDialog() } }, matchWrap()); watch.addView(actionButton("Pair Nearby Phone") { requireGuardianAuth("Arm nearby watch pairing") { GuardStore(this).armWatchPairingWindow(); status.append("\nNearby watch pairing armed for 2 minutes.") } }, matchWrap()); addCard(content, watch)
        val cloud = card("CLOUD", "Remote recovery transport and uploads")
        cloud.addView(TextView(this).apply { text = if (GuardStore(this@MainActivity).recoveryUploadUrl.isBlank()) "NOT CONFIGURED" else "Configured · ${GuardStore(this@MainActivity).cloudTransportState.uppercase()}"; textSize = 14f })
        cloud.addView(actionButton("Recovery Server Settings") { requireGuardianAuth("Recovery upload server") { showRecoveryUploadDialog() } }, matchWrap()); addCard(content, cloud)
        val nearby = card("NEARBY / LOCAL", "Direct phone dashboard for the same Wi-Fi or hotspot")
        val localText = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(55, 67, 59)) }
        localDashboardText = localText
        nearby.addView(localText)
        nearby.addView(actionButton("Refresh Local URL") { updateLocalDashboardText() }, matchWrap())
        addCard(content, nearby)
        val security = card("SECURITY", "Guardian-controlled configuration")
        setPinButton = actionButton("Set Guardian PIN") { showSetGuardianPinDialog() }; security.addView(setPinButton, matchWrap())
        security.addView(actionButton("Laptop Dashboard Pairing") { showDashboardPairDialog() }, matchWrap()); security.addView(actionButton("Guardian Instructions") { showGuardianInstructions() }, matchWrap()); addCard(content, security)
        val setup = card("DEVICE SETUP", "Keep background enforcement reliable on Poco/MIUI")
        setup.addView(actionButton("Enable Search Detection") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, matchWrap()); setup.addView(actionButton("Allow MIUI Autostart") { openMiuiAutostart() }, matchWrap()); setup.addView(actionButton("Allow Unrestricted Battery") { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }, matchWrap()); setup.addView(actionButton("Allow Background Location") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchWrap()); addCard(content, setup)
        val data = card("DATA", "Local-only protection data")
        data.addView(actionButton("Add Personal Reason") { requireGuardianAuth("Add commitment reason") { showReasonDialog() } }, matchWrap()); data.addView(actionButton("Import Local Keyword Pack") { requireGuardianAuth("Import keyword database") { keywordImportAuthorized = true; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }, REQUEST_KEYWORD_PACK) } }, matchWrap()); addCard(content, data)
        val diagnostics = card("DIAGNOSTICS", "Detailed events remain available without cluttering Home")
        diagnostics.addView(TextView(this).apply { text = "Integrity and transport: ${GuardStore(this@MainActivity).tamperScore} security events recorded locally. Cloud transport is ${GuardStore(this@MainActivity).cloudTransportState}."; textSize = 14f }); diagnostics.addView(actionButton("Refresh Status") { requireGuardianAuth("Refresh recovery status") { GuardianRecoverManager(this).refreshStatus(); refreshStatus(); loadAnalytics() } }, matchWrap()); addCard(content, diagnostics)
        val advanced = card("ADVANCED", "Maintenance is guardian-authenticated and temporary")
        advanced.addView(actionButton("Maintenance Mode · 10 min") { requireGuardianAuth("Maintenance Mode") { authorized -> if (LockManager(this).beginMaintenanceMode(authorized)) status.append("\nMaintenance Mode started for 10 minutes.") else status.append("\nMaintenance Mode failed: Device Owner is not active."); refreshStatus() } }, matchWrap()); addCard(content, advanced)
        return page
    }

    private fun refreshStatus() {
        val manager = LockManager(this)
        val store = GuardStore(this)
        val pinStore = GuardianPinStore(this)
        setPinButton.isEnabled = !pinStore.hasPin
        val accessibility = isAccessibilityEnabled()
        if (::urgeButton.isInitialized) {
            urgeButton.text = if (store.urgeActive) "URGE ACTIVE — ${formatDuration(store.urgeRemainingMs())}" else "URGE"
        }
        val integrityScore = listOf(
            manager.isDeviceOwner,
            pinStore.hasPin,
            accessibility,
            store.commitmentStartedAt > 0L,
            store.tamperScore < GuardConfig.TAMPER_LOCK_THRESHOLD
        ).count { it } * 20
        status.text = buildString {
            append(if (manager.isDeviceOwner && pinStore.hasPin && accessibility) "● Protection Active" else "● Protection Incomplete")
            append("\nDevice Owner      ${if (manager.isDeviceOwner) "ACTIVE" else "INCOMPLETE"}")
            append("\nBrowser Protection ${if (accessibility) "ACTIVE" else "ATTENTION"}")
            append("\nGuardian PIN      ${if (pinStore.hasPin) "SET" else "NOT SET"}")
            append("\nCloud             ${store.cloudTransportState.uppercase()}")
            append("\nWatch             ${if (store.watchPublicKeyBase64.isNotBlank()) "CONNECTED" else "NOT PAIRED"}")
            append("\nIntegrity         $integrityScore / 100")
            append("\nSecurity signals recorded locally: ${store.tamperScore}")
            append("\n\nStudy Mode        ${if (store.studyModeActive) "ACTIVE · ${formatDuration(store.effectiveStudyRemainingMs())}" else "READY"}")
            append("\nURGE              ${if (store.urgeActive) "ACTIVE · ${formatDuration(store.urgeRemainingMs())} remaining" else "READY"}")
            append("\nCCTV              ${if (store.cctvMonitorActive) "ACTIVE" else "OFF"}")
            append("\nLost Mode         ${if (store.lostModeActive) "ACTIVE" else "OFF"}")
        }
        updateLocalDashboardText()
    }

    private fun updateLocalDashboardText() {
        val ip = DashboardCommandServer.localIpAddress()
        val valid = ip.isNotBlank() && ip != "0.0.0.0" && ip != "127.0.0.1"
        localDashboardText?.text = if (valid) {
            "Local URL\nhttp://$ip:${DashboardCommandServer.PORT}\nStatus\n● Available on local network"
        } else {
            "Local URL unavailable\nConnect the phone to Wi-Fi or a supported tethering network."
        }
    }

    private fun showUrgeDurationDialog() {
        val store = GuardStore(this)
        if (store.urgeActive) {
            AlertDialog.Builder(this).setTitle("URGE ACTIVE")
                .setMessage("Browser access is blocked. Time remaining: ${formatDuration(store.urgeRemainingMs())}\nEnds: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(store.urgeEndAt))}")
                .setPositiveButton("OK", null).show()
            return
        }
        // Close only the browser route while the user chooses the duration.
        // Hotspot and Settings belong exclusively to a confirmed manual Urge.
        LockManager(this).suspendUrgeTargets()
        val options = arrayOf("1 Hour", "2 Hours", "3 Hours", "4 Hours", "Custom")
        AlertDialog.Builder(this).setTitle("Urge for how many hours?")
            .setItems(options) { _, which ->
                if (which < 4) startUrge(which + 1) else showCustomUrgeDuration()
            }.setNegativeButton("Cancel") { _, _ -> cancelUrgeSelection() }
            .setOnCancelListener { cancelUrgeSelection() }.show()
    }

    private fun showCustomUrgeDuration() {
        val input = EditText(this).apply { hint = "Hours (1–24)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Custom Urge duration").setView(input)
            .setNegativeButton("Back") { _, _ -> cancelUrgeSelection() }.setPositiveButton("Start") { _, _ ->
                startUrge(input.text.toString().toIntOrNull() ?: 0)
            }.setOnCancelListener { cancelUrgeSelection() }.show()
    }

    private fun cancelUrgeSelection() {
        if (!GuardStore(this).urgeActive) LockManager(this).reconcile()
    }

    private fun startUrge(hours: Int) {
        if (hours !in 1..24) { status.append("\nChoose a duration from 1 to 24 hours."); cancelUrgeSelection(); return }
        if (UrgeManager(this).start(hours)) {
            status.append("\nURGE ACTIVE for $hours hour(s). Browser, Settings, and hotspot restrictions applied.")
            refreshStatus()
        } else {
            status.append("\nUrge could not start. Device Owner must be active, or an Urge is already active.")
            cancelUrgeSelection()
        }
    }

    private fun showUrgeHistory() {
        val entries = GuardStore(this).urgeHistory()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val message = if (entries.isEmpty()) "No urges recorded in the last 7 days." else entries.joinToString("\n\n") {
            "${formatter.format(Date(it.startAt))}\n${if (it.exactMinutes) "${it.durationMinutes} minute(s)" else "${it.durationMinutes} hour(s)"}\nEnds ${formatter.format(Date(it.endAt))}\n${if (it.completed) "Completed" else "Active"}"
        }
        AlertDialog.Builder(this).setTitle("Urge History — last 7 days").setMessage(message).setPositiveButton("Close", null).show()
    }

    private fun showAppLimitSettings() {
        val limitManager = AppLimitManager(this)
        val choices = WhitelistManager(this).launchableThirdPartyPackages()
        if (choices.isEmpty()) return
        val selected = limitManager.configuredPackages().toMutableSet()
        val labels = choices.map { it.label }.toTypedArray()
        val checked = choices.map { it.packageName in selected }.toBooleanArray()
        val minutes = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "Daily minutes (default 15)"; setText((limitManager.limitMillis() / 60_000L).toString()) }
        AlertDialog.Builder(this).setTitle("Daily app time limit")
            .setMessage("Select restricted apps. Usage is cumulative per day and enforcement uses Device Owner suspension.")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> if (isChecked) selected += choices[which].packageName else selected -= choices[which].packageName }
            .setView(minutes)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                limitManager.setConfiguredPackages(selected)
                limitManager.setLimitMinutes(minutes.text.toString().toIntOrNull() ?: 15)
                refreshStatus()
            }.show()
    }

    private fun checkPendingWatchPairing() {
        val store = GuardStore(this)
        val key = store.pendingWatchPublicKey
        if (key.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Nearby Guardian Watch pairing request")
            .setMessage("A nearby watch is requesting pairing. Approve only if this is your Galaxy Watch.\n\nKey preview: ${key.take(18)}…")
            .setNegativeButton("Reject") { _, _ -> store.clearPendingWatchPublicKey() }
            .setPositiveButton("Approve") { _, _ ->
                store.watchPublicKeyBase64 = key
                store.clearPendingWatchPublicKey()
                RecoverTimeline(this).record("watch_paired_nearby", "Guardian Watch paired through nearby phone transport")
                status.append("\nGuardian Watch paired through nearby phone.")
                refreshStatus()
            }.show()
    }

    private fun loadAnalytics() {
        Thread {
            val now = System.currentTimeMillis()
            val dao = GuardianDatabase.get(this@MainActivity).guardianDao()
            val week = runBlocking { dao.triggersSince(now - 7L * 86_400_000L) }
            val last = runBlocking { dao.lastTriggerAt() }
            val total = runBlocking { dao.totalTriggerCount() }
            val todayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val tamperToday = runBlocking { dao.recentTamperEvents(200) }.count { it.detectedAt >= todayStart }
            val streakDays = ((now - (last ?: GuardStore(this).commitmentStartedAt.takeIf { it > 0L } ?: now)) / 86_400_000L).toInt()
            val commonHour = week.groupingBy { java.util.Calendar.getInstance().apply { timeInMillis = it.detectedAt }.get(java.util.Calendar.HOUR_OF_DAY) }
                .eachCount().maxByOrNull { it.value }?.key
            val focus = StatisticsManager(this@MainActivity).snapshot()
            val recoverEvents = runBlocking { dao.recentRecoverEvents(5) }
            val latestStatus = runBlocking { dao.latestDeviceStatusSnapshot() }
            runOnUiThread {
                analytics.text = buildString {
                    append("Daily streak: $streakDays days")
                    append("\nWeekly streak: ${streakDays / 7} full weeks")
                    append("\nTotal triggers: $total")
                    append("\nWeekly triggers: ${week.size}")
                    append("\nTamper events today: $tamperToday")
                    append("\nMost common trigger time: ${commonHour?.let { "%02d:00".format(it) } ?: "No data"}")
                    append("\n\nFocus today: ${formatDuration(focus.todayMs)}")
                    append("\nFocus this week: ${formatDuration(focus.weekMs)}")
                    append("\nFocus this month: ${formatDuration(focus.monthMs)}")
                    append("\nLifetime focus: ${formatDuration(focus.lifetimeMs)}")
                    append("\n\nRecover last status: ")
                    append(latestStatus?.let { "battery ${it.batteryPercent}%, ${it.networkSummary}, ${it.locationSummary}" } ?: "No data")
                    append("\nRecover timeline:")
                    if (recoverEvents.isEmpty()) append("\nNo events yet")
                    recoverEvents.forEach {
                        append("\n")
                        append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.createdAt)))
                        append(" ")
                        append(it.type)
                        append(": ")
                        append(it.detail)
                    }
                }
                graph.setEvents(week.map { it.detectedAt })
            }
        }.start()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, SearchGuardAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }

    private fun showSetGuardianPinDialog() {
        if (GuardianPinStore(this).hasPin) return
        val first = pinField("New 6–10 digit PIN")
        val second = pinField("PIN again")
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(first); addView(second) }
        AlertDialog.Builder(this).setTitle("Guardian PIN")
            .setMessage(guardianInstructionsText())
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val a = first.text.toString(); val b = second.text.toString()
                val saved = a == b && GuardianPinStore(this).setOnce(a.toCharArray())
                first.text.clear(); second.text.clear()
                if (saved) {
                    LockManager(this).beginCommitmentIfNeeded()
                } else {
                    status.append("\nPIN was not saved. Use matching 6–10 digit numbers.")
                }
                refreshStatus()
            }.show()
    }

    private fun showGuardianInstructions() {
        AlertDialog.Builder(this)
            .setTitle("${GuardConfig.COMMITMENT_DAYS}-day Guardian Commitment")
            .setMessage(guardianInstructionsText())
            .setPositiveButton("I understand", null)
            .show()
    }

    private fun guardianInstructionsText(): String = """
        Guardian only:

        1. Take the phone from the user before setting the PIN.
        2. Create a private 6–10 digit PIN that the user cannot guess.
        3. Do not tell the PIN to the user before ${GuardConfig.COMMITMENT_DAYS} days.
        4. Keep emergency calling, health, school, work, banking, and family access available.

        There is no in-app PIN recovery. If Device Owner mode is enabled, this PIN controls protected settings.
    """.trimIndent()

    @Deprecated("Uses the platform document picker result for Android 11 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_KEYWORD_PACK || resultCode != RESULT_OK || !keywordImportAuthorized) return
        keywordImportAuthorized = false
        val uri = data?.data ?: return
        Thread {
            val outcome = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    runBlocking { KeywordPackImporter.import(this@MainActivity, input) }
                } ?: error("Unable to open selected file")
            }
            runOnUiThread {
                status.append(outcome.fold(
                    onSuccess = { "\nImported $it local keywords." },
                    onFailure = { "\nKeyword import failed: ${it.message}" }
                ))
            }
        }.start()
    }

    private fun showReasonDialog() {
        val input = EditText(this).apply { hint = "Why do you want to recover?"; maxLines = 4 }
        AlertDialog.Builder(this).setTitle("Personal recovery reason").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim().take(500)
                if (text.isNotEmpty()) Thread { runBlocking { GuardianDatabase.get(this@MainActivity).guardianDao().insertReason(PersonalReasonEntity(text = text)) } }.start()
            }.show()
    }

    private fun showLostModeDialog() {
        val input = EditText(this).apply {
            hint = "Contact message shown on phone"
            setText(GuardStore(this@MainActivity).lostModeMessage.ifBlank { "This phone is protected by Guardian Lock. Please contact the owner." })
            maxLines = 4
        }
        AlertDialog.Builder(this).setTitle("Lost Mode").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Enable") { _, _ ->
                showRecoverResult(GuardianRecoverManager(this).enterLostMode(input.text.toString()))
                refreshStatus()
                loadAnalytics()
            }.show()
    }

    private fun showDashboardPairDialog() {
        val input = EditText(this).apply {
            hint = "Paste laptop dashboard public key"
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Pair Laptop Dashboard")
            .setMessage("This key lets your laptop send signed commands without the guardian PIN. Keep the dashboard file private.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pair") { _, _ ->
                val key = input.text.toString().trim()
                if (key.length >= 60) {
                    GuardStore(this).dashboardPublicKeyBase64 = key
                    RecoverTimeline(this).record("dashboard_paired", "Laptop public key paired from phone UI")
                    status.append("\nLaptop dashboard paired.")
                } else {
                    status.append("\nPairing failed: public key is too short.")
                }
                refreshStatus()
            }.show()
    }

    private fun showWatchPairDialog() {
        val input = EditText(this).apply {
            hint = "Enter 7-digit watch pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("Pair Guardian Watch")
            .setMessage("Open Guardian Watch → Settings / Pairing → generate the 7-digit code. Your watch never receives the guardian PIN.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().filter { it.isDigit() }
                if (!Regex("^\\d{7}$").matches(code)) {
                    status.append("\nWatch pairing failed: enter exactly 7 digits.")
                    return@setPositiveButton
                }
                status.append("\nPairing watch with code $code...")
                Thread {
                    val key = RecoverUploadManager(this).fetchWatchPublicKeyByCode(code)
                    runOnUiThread {
                        if (key != null) {
                            GuardStore(this).watchPublicKeyBase64 = key
                            RecoverTimeline(this).record("watch_paired", "Guardian Watch paired with 7-digit code")
                            status.append("\nGuardian Watch paired.")
                        } else {
                            status.append("\nWatch pairing failed: code expired, server not configured, or token mismatch.")
                        }
                        refreshStatus()
                    }
                }
                    .start()
            }.show()
    }

    private fun showRecoveryUploadDialog() {
        val store = GuardStore(this)
        val url = EditText(this).apply {
            hint = "https://your-server.example.com"
            setText(store.recoveryUploadUrl)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(this).apply {
            hint = "Upload token"
            setText(store.recoveryUploadToken)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(url)
            addView(token)
        }
        AlertDialog.Builder(this)
            .setTitle("Recovery Upload Server")
            .setMessage("Set this before the phone is lost. The phone will upload Lost Mode status, photos, and audio to this server when internet is available.")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                store.recoveryUploadUrl = url.text.toString()
                store.recoveryUploadToken = token.text.toString()
                RecoverTimeline(this).record("upload_server_configured", store.recoveryUploadUrl)
                status.append("\nRecovery upload server saved.")
                refreshStatus()
            }
            .show()
    }

    private fun showRecoverResult(result: RecoverCommandResult) {
        val text = when (result) {
            RecoverCommandResult.Applied -> "Recover command applied."
            is RecoverCommandResult.Unsupported -> "Recover limitation: ${result.reason}"
            is RecoverCommandResult.Failed -> "Recover command failed: ${result.reason}"
        }
        status.append("\n$text")
    }

    private fun showEntertainmentSelection() {
        val installed = GuardConfig.suggestedEntertainmentPackages.filter { packageManager.getLaunchIntentForPackage(it) != null }
        if (installed.isEmpty()) { status.append("\nNo suggested entertainment apps are installed."); return }
        val labels = installed.map { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.toTypedArray()
        val selected = GuardStore(this).selectedEntertainmentPackages.toMutableSet()
        val checks = BooleanArray(installed.size) { installed[it] in selected }
        AlertDialog.Builder(this).setTitle("Apps blocked from escalation level 3")
            .setMultiChoiceItems(labels, checks) { _, which, checked -> if (checked) selected += installed[which] else selected -= installed[which] }
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> GuardStore(this).updateEntertainmentSelection(selected) }
            .show()
    }

    private fun showStudyModeDialog() {
        val manager = StudyModeManager(this)
        if (GuardStore(this).studyModeActive) {
            startActivity(Intent(this, StudyModeActivity::class.java))
            return
        }
        val minutes = EditText(this).apply {
            hint = "Minutes (25, 45, 90, 120…)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val camera = android.widget.CheckBox(this).apply { text = "Allow camera" }
        val calculator = android.widget.CheckBox(this).apply { text = "Allow calculator" }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(minutes); addView(camera); addView(calculator) }
        AlertDialog.Builder(this).setTitle("Start Study Mode")
            .setMessage("Only phone, emergency calling, system safety functions, Guardian Lock, and selected optional tools will remain available. It ends automatically.")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Start") { _, _ ->
                val duration = minutes.text.toString().toLongOrNull()?.times(60_000L) ?: 0L
                val allowed = WhitelistManager(this).defaultAllowedPackages(camera.isChecked, calculator.isChecked)
                showStudyWhitelist(duration, allowed, manager)
            }.show()
    }

    private fun showStudyWhitelist(duration: Long, defaultAllowed: Set<String>, manager: StudyModeManager) {
        val choices = WhitelistManager(this).launchableThirdPartyPackages()
            .filterNot { it.packageName in defaultAllowed || it.packageName == "com.android.settings" || it.packageName == "com.miui.securitycenter" }
        if (choices.isEmpty()) { startStudySession(duration, defaultAllowed, manager); return }
        val selected = mutableSetOf<String>()
        AlertDialog.Builder(this).setTitle("Optional study apps")
            .setMessage("Choose only apps genuinely needed for this one session. The list freezes when Study Mode begins.")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), BooleanArray(choices.size)) { _, which, checked ->
                if (checked) selected += choices[which].packageName else selected -= choices[which].packageName
            }.setNegativeButton("Use default") { _, _ -> startStudySession(duration, defaultAllowed, manager) }
            .setPositiveButton("Start session") { _, _ -> startStudySession(duration, defaultAllowed + selected, manager) }
            .show()
    }

    private fun startStudySession(duration: Long, allowed: Set<String>, manager: StudyModeManager) {
        if (!manager.start(duration, allowed)) status.append("\nStudy Mode could not start. Choose 1 minute to 8 hours and ensure Device Owner is active.")
        refreshStatus()
    }

    private fun requireGuardianAuth(title: String, action: (Boolean) -> Unit) {
        val pin = pinField("Guardian PIN")
        AlertDialog.Builder(this).setTitle(title).setView(pin).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val ok = GuardianPinStore(this).verify(pin.text.toString().toCharArray())
                pin.text.clear()
                if (ok) action(true) else status.append("\nIncorrect guardian PIN.")
            }.show()
    }

    private fun openMiuiAutostart() {
        val intent = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setTextColor(Color.rgb(27, 54, 42))
        background = GradientDrawable().apply { setColor(Color.rgb(232, 240, 233)); cornerRadius = 16f }
        setOnClickListener { action() }
    }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }

    companion object { private const val REQUEST_KEYWORD_PACK = 2201 }
}
