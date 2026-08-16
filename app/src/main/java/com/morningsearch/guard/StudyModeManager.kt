package com.morningsearch.guard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import com.morningsearch.guard.data.FocusSessionEntity
import com.morningsearch.guard.data.GuardianDatabase
import com.morningsearch.guard.data.LocalAuditRepository
import kotlinx.coroutines.runBlocking

/** Applies the voluntary, time-limited Study Mode without changing Guardian Lock's existing rules. */
class StudyModeManager(private val context: Context) {
    private val store = GuardStore(context)
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
    private val whitelist = WhitelistManager(context)
    private val audit = LocalAuditRepository(context)

    fun start(durationMs: Long, allowedPackages: Set<String>): Boolean {
        if (!policy.isDeviceOwnerApp(context.packageName) || durationMs !in MIN_DURATION_MS..MAX_DURATION_MS || store.studyModeActive) return false
        val allowed = (allowedPackages + WhitelistManager(context).defaultAllowedPackages(false, false) + context.packageName).toSet()
        val now = System.currentTimeMillis()
        val sessionId = runBlocking {
            GuardianDatabase.get(context).guardianDao().insertFocusSession(
                FocusSessionEntity(startedAt = now, plannedEndAt = now + durationMs, durationMs = durationMs, allowedPackages = allowed.sorted().joinToString(","))
            )
        }
        store.beginStudyMode(durationMs, allowed, sessionId)
        applyStudyPolicies()
        suspendNonAllowedApps(allowed, true)
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
        openSessionScreen()
        return true
    }

    fun reconcile() {
        if (!store.studyModeActive) {
            if (store.studyModeSessionId != 0L) finish(completed = true)
            return
        }
        applyStudyPolicies()
        suspendNonAllowedApps(store.studyAllowedPackages, true)
    }

    fun handleBlockedLaunch(packageName: String): Boolean {
        if (!store.studyModeActive || packageName in store.studyAllowedPackages || isEssentialPackage(packageName)) return false
        val count = store.incrementStudyInterruption()
        audit.logTamper("study_interruption", "Blocked launch during Study Mode: $packageName (#$count)")
        openSessionScreen()
        return true
    }

    fun finish(completed: Boolean) {
        val sessionId = store.studyModeSessionId
        val interruptions = store.studyInterruptedCount
        if (sessionId != 0L) runBlocking {
            GuardianDatabase.get(context).guardianDao().finishFocusSession(sessionId, System.currentTimeMillis(), completed, interruptions)
        }
        unsuspendStudyTargets()
        clearStudyPolicies()
        store.clearStudyMode()
        LockManager(context).reconcile()
        LockManager(context).enforceAccessibilityState(accessibilityEnabled())
    }

    private fun openSessionScreen() {
        context.startActivity(Intent(context, StudyModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun applyStudyPolicies() {
        runCatching { policy.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        runCatching { policy.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
    }

    private fun clearStudyPolicies() {
        runCatching { policy.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        runCatching { policy.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
    }

    private fun suspendNonAllowedApps(allowed: Set<String>, suspend: Boolean) {
        val packages = whitelist.launchableThirdPartyPackages().map { it.packageName }
            .filterNot { it in allowed || isEssentialPackage(it) || it == context.packageName }
        packages.chunked(50).forEach { chunk ->
            runCatching { policy.setPackagesSuspended(admin, chunk.toTypedArray(), suspend) }
                .onFailure { audit.logTamper("study_policy_failure", it.javaClass.simpleName) }
        }
    }

    private fun unsuspendStudyTargets() = suspendNonAllowedApps(emptySet(), false)

    private fun isEssentialPackage(packageName: String): Boolean = packageName == "android" ||
        packageName == "com.android.systemui" || packageName.contains("emergency") ||
        packageName.contains("telecom") || packageName.contains("phone") || packageName.contains("dialer")

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(context, SearchGuardAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }

    companion object {
        const val MIN_DURATION_MS = 60_000L
        const val MAX_DURATION_MS = 8L * 60L * 60L * 1000L
    }
}
