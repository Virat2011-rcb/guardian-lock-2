package com.morningsearch.guard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
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
    private var keywordImportAuthorized = false
    private var renderedDeviceOwner = false

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
        }
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

    private fun buildScreen(): ScrollView {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
            setBackgroundColor(Color.rgb(244, 241, 232))
        }
        content.addView(TextView(this).apply {
            text = "Guardian Lock"
            textSize = 30f
            setTextColor(Color.rgb(27, 54, 42))
        })
        content.addView(TextView(this).apply {
            text = "Private, on-device recovery protection for a ${GuardConfig.COMMITMENT_DAYS}-day commitment. No history, screenshots, or search text are uploaded."
            textSize = 16f
            setPadding(0, padding, 0, padding)
        })
        content.addView(TextView(this).apply {
            text = "Guardian setup: give the phone to your mummy/guardian. They must set a secret 6–10 digit PIN privately and must not tell it to you until the commitment ends, unless there is a real safety emergency."
            textSize = 15f
            setPadding(0, 0, 0, padding)
            setTextColor(Color.rgb(93, 74, 37))
        })
        status = TextView(this).apply { textSize = 16f }
        content.addView(status)
        analytics = TextView(this).apply { textSize = 17f }
        content.addView(analytics)
        graph = WeeklyProgressView(this)
        content.addView(graph, matchWrap())
        content.addView(actionButton("Guardian Recover: refresh status") {
            requireGuardianAuth("Refresh recovery status") {
                val snapshot = GuardianRecoverManager(this).refreshStatus()
                status.append("\nRecover status: battery ${snapshot.batteryPercent}%, ${snapshot.networkSummary}, location ${snapshot.locationSummary}")
                loadAnalytics()
            }
        }, matchWrap())
        content.addView(actionButton("Pair laptop dashboard") { showDashboardPairDialog() }, matchWrap())
        content.addView(actionButton("Guardian: set recovery upload server") {
            requireGuardianAuth("Recovery upload server") { showRecoveryUploadDialog() }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: Lost Mode") {
            requireGuardianAuth("Enable Lost Mode") { showLostModeDialog() }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: Found My Device") {
            requireGuardianAuth("Found My Device") {
                showRecoverResult(GuardianRecoverManager(this).foundDevice())
                refreshStatus()
                loadAnalytics()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: lock now") {
            requireGuardianAuth("Lock device") {
                showRecoverResult(GuardianRecoverManager(this).lockDevice(customPinRequested = true))
                loadAnalytics()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: start siren") {
            requireGuardianAuth("Start siren") {
                showRecoverResult(GuardianRecoverManager(this).startAlarm())
                refreshStatus()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: stop siren") {
            requireGuardianAuth("Stop siren") {
                showRecoverResult(GuardianRecoverManager(this).stopAlarm())
                refreshStatus()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: flashlight blink") {
            requireGuardianAuth("Flashlight blink") {
                showRecoverResult(GuardianRecoverManager(this).startFlashlight())
                refreshStatus()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: stop flashlight") {
            requireGuardianAuth("Stop flashlight") {
                showRecoverResult(GuardianRecoverManager(this).stopFlashlight())
                refreshStatus()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: front camera photo") {
            requireGuardianAuth("Front camera photo") {
                showRecoverResult(GuardianRecoverManager(this).requestCapture("capture_front"))
                loadAnalytics()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: rear camera photo") {
            requireGuardianAuth("Rear camera photo") {
                showRecoverResult(GuardianRecoverManager(this).requestCapture("capture_rear"))
                loadAnalytics()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian Recover: record audio") {
            requireGuardianAuth("Record audio") {
                showRecoverResult(GuardianRecoverManager(this).requestCapture("record_audio"))
                loadAnalytics()
            }
        }, matchWrap())
        content.addView(actionButton("Start Study Mode") { showStudyModeDialog() }, matchWrap())
        setPinButton = actionButton("Set guardian PIN") { showSetGuardianPinDialog() }
        content.addView(setPinButton, matchWrap())
        content.addView(actionButton("Show guardian instructions") { showGuardianInstructions() }, matchWrap())
        content.addView(actionButton("Enable search detection") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrap())
        content.addView(actionButton("Guardian: add a personal commitment reason") {
            requireGuardianAuth("Add commitment reason") { showReasonDialog() }
        }, matchWrap())
        content.addView(actionButton("Guardian: choose entertainment apps") {
            requireGuardianAuth("Change protected entertainment apps") { showEntertainmentSelection() }
        }, matchWrap())
        content.addView(actionButton("Guardian: import local keyword pack") {
            requireGuardianAuth("Import keyword database") {
                keywordImportAuthorized = true
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    },
                    REQUEST_KEYWORD_PACK
                )
            }
        }, matchWrap())
        content.addView(actionButton("Poco/MIUI: allow background operation") { openMiuiAutostart() }, matchWrap())
        content.addView(actionButton("Allow unrestricted battery use") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }, matchWrap())
        content.addView(actionButton("Guardian: maintenance mode (10 min)") {
            requireGuardianAuth("Maintenance Mode") { authorized ->
                if (LockManager(this).beginMaintenanceMode(authorized)) {
                    status.append("\nMaintenance Mode started for 10 minutes. Developer Options are temporarily allowed.")
                } else {
                    status.append("\nMaintenance Mode failed: Device Owner is not active.")
                }
                refreshStatus()
            }
        }, matchWrap())
        content.addView(actionButton("Guardian: emergency release") {
            requireGuardianAuth("Release Device Owner protection") { authorized ->
                if (!LockManager(this).releaseProtection(authorized)) status.append("\nRelease failed: Device Owner is not active.")
                refreshStatus()
            }
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "Emergency calling, Settings, and essential apps remain available. Recovery, therapy, and medical-help searches are allowed. No app can make a phone impossible to reset forever; Device Owner mode gives the strongest normal protection."
            textSize = 14f
            setPadding(0, padding, 0, 0)
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun refreshStatus() {
        val manager = LockManager(this)
        val store = GuardStore(this)
        val pinStore = GuardianPinStore(this)
        setPinButton.isEnabled = !pinStore.hasPin
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val accessibility = isAccessibilityEnabled()
        val integrityScore = listOf(
            manager.isDeviceOwner,
            pinStore.hasPin,
            accessibility,
            store.commitmentStartedAt > 0L,
            store.tamperScore < GuardConfig.TAMPER_LOCK_THRESHOLD
        ).count { it } * 20
        status.text = buildString {
            append(if (manager.isDeviceOwner && pinStore.hasPin && accessibility) "Protection Active" else "Protection Incomplete")
            append("\nIntegrity score: $integrityScore/100")
            append(if (manager.isDeviceOwner) "\nDevice Owner: active" else "\nDevice Owner: not provisioned")
            append(if (pinStore.hasPin) "\nGuardian PIN: set" else "\nGuardian PIN: not set")
            append(if (accessibility) "\nAccessibility: active" else "\nAccessibility: not active")
            append("\nTamper score: ${store.tamperScore}/${GuardConfig.TAMPER_LOCK_THRESHOLD}")
            append(if (store.studyModeActive) "\nStudy Mode: active — ${formatDuration(store.effectiveStudyRemainingMs())} remaining" else "\nStudy Mode: ready")
            append(if (store.lostModeActive) "\nLost Mode: active" else "\nLost Mode: off")
            append(if (store.recoverAlarmActive) "\nRecover siren: active" else "\nRecover siren: off")
            append(if (store.recoverFlashlightActive) "\nRecover flashlight: active" else "\nRecover flashlight: off")
            append(if (store.dashboardPublicKeyBase64.isNotBlank()) "\nLaptop dashboard: paired" else "\nLaptop dashboard: not paired")
            append("\nDashboard URL: http://${DashboardCommandServer.localIpAddress()}:${DashboardCommandServer.PORT}")
            append(if (store.recoveryUploadUrl.isNotBlank()) "\nRecovery upload: configured" else "\nRecovery upload: not configured")
            append(if (store.maintenanceModeActive) "\nMaintenance Mode: active — ${formatDuration(store.maintenanceRemainingMs())} remaining" else "\nMaintenance Mode: off")
            if (store.isPending) append("\nUrge delay: active")
            if (store.isLocked) append("\nLock ends: ${format.format(Date(store.lockUntilWall))}")
            if (store.commitmentStartedAt > 0L) append("\nCommitment ends: ${format.format(Date(store.commitmentEndsAt))}")
            append("\n")
        }
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
        4. Use emergency release only for device-safety problems.
        5. Keep emergency calling, health, school, work, banking, and family access available.

        There is no in-app PIN recovery. If Device Owner mode is enabled, this PIN controls protected settings and release.
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

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }

    companion object { private const val REQUEST_KEYWORD_PACK = 2201 }
}
