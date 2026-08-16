package com.morningsearch.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.morningsearch.guard.data.LocalAuditRepository

class LockManager(private val context: Context) {
    private val store = GuardStore(context)
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
    private val audit = LocalAuditRepository(context)

    val isDeviceOwner: Boolean get() = policy.isDeviceOwnerApp(context.packageName)

    fun beginCommitmentIfNeeded() {
        if (!isDeviceOwner || !GuardianPinStore(context).hasPin) return
        if (store.commitmentStartedAt == 0L) store.commitmentStartedAt = System.currentTimeMillis()
        policy.setUninstallBlocked(admin, context.packageName, true)
        applyDebuggingPolicy()
        policy.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        policy.setAutoTimeRequired(admin, true)
        policy.setUserControlDisabledPackages(admin, listOf(context.packageName))
    }

    fun beginMaintenanceMode(guardianAuthorized: Boolean, durationMs: Long = MAINTENANCE_DURATION_MS): Boolean {
        if (!guardianAuthorized || !isDeviceOwner) return false
        store.beginMaintenanceMode(durationMs)
        applyDebuggingPolicy()
        audit.logTamper("maintenance_mode_started", "Developer options temporarily allowed for ${durationMs / 60_000L} minutes")
        scheduleEvaluation(store.maintenanceEndsAt)
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
        return true
    }

    fun beginUrgeDelay(sourcePackage: String, category: String) {
        if (store.isPending || store.isLocked) return
        val plan = store.recordTriggerAndPlan()
        store.beginPendingLock(plan)
        audit.logTrigger(sourcePackage, category, plan)
        scheduleEvaluation(store.pendingActivateAt)
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
        context.startActivity(
            Intent(context, UrgeDelayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun beginTamperLock(reason: String) {
        if (!isDeviceOwner) return
        store.beginImmediateLock(GuardConfig.TAMPER_LOCK_DURATION_MS, includeEntertainment = true)
        audit.logTamper("tamper_lock", "Immediate level-3 lock activated: $reason")
        suspendLockTargets(true)
        scheduleEvaluation(System.currentTimeMillis() + GuardConfig.TAMPER_LOCK_DURATION_MS)
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
    }

    fun reconcile() {
        beginCommitmentIfNeeded()
        applyDebuggingPolicy()
        StudyModeManager(context).reconcile()
        if (store.isPending) {
            if (System.currentTimeMillis() >= store.pendingActivateAt) {
                store.activatePendingLock()
            } else {
                scheduleEvaluation(store.pendingActivateAt)
            }
        }
        if (store.isLocked) {
            suspendLockTargets(true)
            scheduleEvaluation(System.currentTimeMillis() + store.effectiveRemainingMs())
        } else if (!store.isPending) {
            unsuspendAllManagedTargets()
        }
        if (store.maintenanceModeActive) {
            scheduleEvaluation(store.maintenanceEndsAt)
        }
    }

    fun releaseProtection(guardianAuthorized: Boolean): Boolean {
        if (!guardianAuthorized || !isDeviceOwner) return false
        unsuspendAllManagedTargets()
        policy.setUserControlDisabledPackages(admin, emptyList())
        policy.setUninstallBlocked(admin, context.packageName, false)
        policy.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        policy.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        policy.setAutoTimeRequired(admin, false)
        policy.clearDeviceOwnerApp(context.packageName)
        store.endProtection()
        GuardianPinStore(context).clearAfterVerifiedRelease()
        context.stopService(Intent(context, EnforcementService::class.java))
        return true
    }

    fun isBlockedPackage(packageName: String): Boolean {
        if (!store.isLocked && !store.isPending) return false
        if (BrowserRegistry(context).isBrowser(packageName)) return true
        return store.isLocked && store.lockIncludesEntertainment &&
            packageName in store.selectedEntertainmentPackages
    }

    fun enforceAccessibilityState(enabled: Boolean) {
        if (!isDeviceOwner) return
        if (!enabled) {
            setSuspended(BrowserRegistry(context).installedBrowsers(), true)
        } else {
            reconcile()
        }
    }

    fun suspendPackageIfDeviceOwner(packageName: String) {
        if (!isDeviceOwner || packageName == context.packageName) return
        setSuspended(setOf(packageName), true)
    }

    private fun suspendLockTargets(suspend: Boolean) {
        if (!isDeviceOwner) return
        val targets = BrowserRegistry(context).installedBrowsers().toMutableSet()
        if (store.lockIncludesEntertainment) targets += store.selectedEntertainmentPackages
        setSuspended(targets, suspend)
    }

    private fun unsuspendAllManagedTargets() {
        val targets = BrowserRegistry(context).installedBrowsers() +
            store.selectedEntertainmentPackages + GuardConfig.suggestedEntertainmentPackages
        if (isDeviceOwner) setSuspended(targets, false)
        store.clearLock()
    }

    private fun applyDebuggingPolicy() {
        if (!isDeviceOwner) return
        if (store.maintenanceModeActive) {
            policy.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        } else {
            if (store.maintenanceEndsAt > 0L) {
                store.clearMaintenanceMode()
                audit.logTamper("maintenance_mode_ended", "Developer options restriction restored")
            }
            policy.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
    }

    private fun setSuspended(packages: Set<String>, suspended: Boolean) {
        packages.filter { it != context.packageName }.forEach { packageName ->
            runCatching { policy.setPackagesSuspended(admin, arrayOf(packageName), suspended) }
                .onFailure { audit.logTamper("package_policy_failure", "$packageName: ${it.javaClass.simpleName}") }
        }
    }

    private fun scheduleEvaluation(atMillis: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            1001,
            Intent(context, UnlockReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    companion object {
        const val MAINTENANCE_DURATION_MS = 10L * 60L * 1000L
    }
}
