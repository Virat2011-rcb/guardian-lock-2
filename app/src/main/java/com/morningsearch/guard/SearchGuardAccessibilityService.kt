package com.morningsearch.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.morningsearch.guard.data.GuardianDatabase
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class SearchGuardAccessibilityService : AccessibilityService() {
    private var lastTriggerAt = 0L
    private var lastSettingsTamperAt = 0L
    private val browserCache = ConcurrentHashMap<String, Boolean>()

    override fun onServiceConnected() {
        EnforcementService.start(this)
        Thread {
            val terms = runBlocking { GuardianDatabase.get(this@SearchGuardAccessibilityService).guardianDao().enabledKeywords() }
            SearchClassifier.updateLocalDatabase(terms)
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        val manager = LockManager(this)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            AppLimitManager(this).onForegroundPackage(packageName)
        }

        if (StudyModeManager(this).handleBlockedLaunch(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (GuardStore(this).urgeActive &&
            (packageName == "com.android.settings" || packageName == "com.miui.securitycenter")) {
            TamperManager(this).record("urge_settings_blocked", "Settings access blocked during active Urge Lock", 5)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (packageName == "com.android.settings" || packageName == "com.miui.securitycenter") {
            detectSettingsTamper(event)
        }

        if (manager.isBlockedPackage(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (GuardStore(this).isPending) {
                startActivity(Intent(this, UrgeDelayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }

        val isBrowser = browserCache.getOrPut(packageName) {
            packageName in GuardConfig.browserPackages || BrowserRegistry(this).isBrowser(packageName)
        }
        if (!isBrowser) return

        val candidates = buildList {
            event.text.mapNotNullTo(this) { it?.toString() }
            event.contentDescription?.toString()?.let(::add)
            collectEditableText(event.source, this, 0)
        }
        val detection = candidates.asSequence().mapNotNull(SearchClassifier::classify).firstOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt > 3_000L) {
            lastTriggerAt = now
            manager.beginUrgeDelay(packageName, detection.category)
        }
    }

    private fun detectSettingsTamper(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastSettingsTamperAt < 30_000L) return
        val text = buildList {
            event.text.mapNotNullTo(this) { it?.toString() }
            event.contentDescription?.toString()?.let(::add)
            collectVisibleText(event.source, this, 0)
        }.joinToString(" ").lowercase()
        val mentionsGuardian = text.contains("guardian lock") || text.contains(packageName)
        val dangerousAction = listOf(
            "uninstall",
            "force stop",
            "clear data",
            "clear storage",
            "disable",
            "remove",
            "accessibility"
        ).any(text::contains)
        if (mentionsGuardian && dangerousAction) {
            lastSettingsTamperAt = now
            TamperManager(this).record("settings_tamper_screen", "Guardian Lock settings/uninstall screen opened", 20)
        }
    }

    private fun collectEditableText(node: AccessibilityNodeInfo?, output: MutableList<String>, depth: Int) {
        if (node == null || depth > 8 || output.size > 30) return
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            node.text?.toString()?.take(500)?.let(output::add)
            node.contentDescription?.toString()?.take(500)?.let(output::add)
        }
        for (index in 0 until node.childCount) collectEditableText(node.getChild(index), output, depth + 1)
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, output: MutableList<String>, depth: Int) {
        if (node == null || depth > 8 || output.size > 60) return
        node.text?.toString()?.take(200)?.let(output::add)
        node.contentDescription?.toString()?.take(200)?.let(output::add)
        for (index in 0 until node.childCount) collectVisibleText(node.getChild(index), output, depth + 1)
    }

    override fun onInterrupt() = Unit
}
