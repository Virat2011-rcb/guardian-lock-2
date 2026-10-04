package com.morningsearch.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.morningsearch.guard.data.LocalAuditRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, cumulative daily limits for guardian-selected social apps. */
class AppLimitManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_limits", Context.MODE_PRIVATE)
    private val store = GuardStore(context)
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
    private val audit = LocalAuditRepository(context)

    fun configuredPackages(): Set<String> = prefs.getStringSet(KEY_PACKAGES, DEFAULT_PACKAGES)?.toSet().orEmpty()
        .filter { it != context.packageName }.toSet()

    fun setConfiguredPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_PACKAGES, packages.filter { it != context.packageName }.toSet()).apply()
        reconcilePolicy()
    }

    fun limitMillis(): Long = prefs.getLong(KEY_LIMIT, DEFAULT_LIMIT_MS).coerceIn(60_000L, 24L * 60L * 60L * 1000L)

    fun setLimitMinutes(minutes: Int) {
        prefs.edit().putLong(KEY_LIMIT, minutes.coerceIn(1, 1440) * 60_000L).apply()
        reconcilePolicy()
    }

    /** Called for every foreground package transition from Accessibility. */
    fun onForegroundPackage(packageName: String) {
        resetIfNeeded()
        if (packageName == activePackage()) {
            tick()
            return
        }
        pauseActive()
        if (packageName !in configuredPackages() || store.studyModeActive) {
            cancelNotification()
            return
        }
        if (remaining(packageName) <= 0L) {
            suspend(packageName, true)
            notifyLimitReached(packageName)
            audit.logTamper("app_limit_blocked", "$packageName has reached its daily limit")
            return
        }
        prefs.edit().putString(KEY_ACTIVE, packageName)
            .putLong(KEY_START_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_START_WALL, System.currentTimeMillis())
            .putInt(KEY_START_BOOT, bootCount()).apply()
        audit.logTamper("app_limit_session_started", packageName)
        notifyRemaining(packageName)
    }

    /** Called by the foreground enforcement service; persists and repairs suspension state. */
    fun tick() {
        resetIfNeeded()
        val active = activePackage() ?: run { reconcilePolicy(); return }
        if (store.studyModeActive) {
            pauseActive()
            cancelNotification()
            reconcilePolicy()
            return
        }
        val left = remaining(active)
        if (left <= 0L) {
            pauseActive()
            suspend(active, true)
            notifyLimitReached(active)
            audit.logTamper("app_limit_reached", active)
        } else {
            notifyRemaining(active)
        }
        reconcilePolicy()
    }

    fun reconcilePolicy() {
        resetIfNeeded()
        val configured = configuredPackages()
        if (!isDeviceOwner()) return
        configured.forEach { pkg ->
            val mustBlock = store.studyModeActive || remaining(pkg) <= 0L
            suspend(pkg, mustBlock)
        }
    }

    fun summary(): String = configuredPackages().joinToString("\n") { pkg ->
        val used = (limitMillis() - remaining(pkg)).coerceAtLeast(0L)
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)) }.getOrNull() ?: pkg
        "$label: ${format(used)} / ${format(limitMillis())}${if (remaining(pkg) <= 0L) " — LIMIT REACHED" else " (${format(remaining(pkg))} left)"}"
    }

    private fun pauseActive() {
        val pkg = activePackage() ?: return
        val delta = sessionElapsed()
        if (delta > 0L) addUsed(pkg, delta)
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_START_ELAPSED).remove(KEY_START_WALL).apply()
        cancelNotification()
        audit.logTamper("app_limit_session_paused", pkg)
    }

    private fun sessionElapsed(): Long {
        val startedElapsed = prefs.getLong(KEY_START_ELAPSED, 0L)
        val startedWall = prefs.getLong(KEY_START_WALL, 0L)
        val elapsed = if (prefs.getInt(KEY_START_BOOT, -1) == bootCount() && startedElapsed > 0L) {
            (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
        } else {
            (System.currentTimeMillis() - startedWall).coerceAtLeast(0L)
        }
        return elapsed.coerceAtMost(limitMillis())
    }

    private fun addUsed(pkg: String, delta: Long) {
        val key = usedKey(pkg)
        val updated = (prefs.getLong(key, 0L) + delta).coerceAtMost(limitMillis())
        prefs.edit().putLong(key, updated).apply()
    }

    private fun remaining(pkg: String): Long = (limitMillis() - prefs.getLong(usedKey(pkg), 0L) -
        if (pkg == activePackage()) sessionElapsed() else 0L).coerceAtLeast(0L)

    private fun resetIfNeeded() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString(KEY_DATE, null) == today) return
        pauseActive()
        val edit = prefs.edit().putString(KEY_DATE, today)
        configuredPackages().forEach { edit.putLong(usedKey(it), 0L) }
        edit.apply()
        if (isDeviceOwner()) configuredPackages().forEach { suspend(it, false) }
        audit.logTamper("daily_limit_reset", today)
    }

    private fun suspend(packageName: String, value: Boolean) {
        if (!isDeviceOwner() || packageName == context.packageName) return
        runCatching {
            policy.setPackagesSuspended(admin, arrayOf(packageName), value)
            if (policy.isPackageSuspended(admin, packageName) != value) {
                audit.logTamper("package_suspension_failed", "$packageName expected=$value")
            }
        }.onFailure { audit.logTamper("package_suspension_failed", "$packageName: ${it.javaClass.simpleName}") }
    }

    private fun isDeviceOwner() = policy.isDeviceOwnerApp(context.packageName)
    private fun activePackage(): String? = prefs.getString(KEY_ACTIVE, null)
    private fun usedKey(pkg: String) = "used_${pkg.replace('.', '_')}"
    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun notifyRemaining(pkg: String) {
        val left = remaining(pkg)
        val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)) }.getOrNull() ?: pkg
        notificationManager().notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("$name time remaining")
            .setContentText("${format(left)} left today; app will be blocked at zero")
            .setWhen(System.currentTimeMillis() - (limitMillis() - left))
            .setUsesChronometer(false).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS).build())
    }

    private fun notifyLimitReached(pkg: String) {
        val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)) }.getOrNull() ?: pkg
        notificationManager().notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("$name limit reached")
            .setContentText("Daily ${limitMillis() / 60_000L}-minute limit reached.").setOngoing(false).build())
    }

    private fun cancelNotification() = notificationManager().cancel(NOTIFICATION_ID)
    private fun notificationManager(): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App time limits", NotificationManager.IMPORTANCE_LOW))
        return manager
    }
    private fun format(ms: Long) = "%02d:%02d".format(Locale.US, ms / 60_000L, (ms / 1000L) % 60L)

    companion object {
        private const val KEY_PACKAGES = "restricted_packages"
        private const val KEY_LIMIT = "daily_limit_ms"
        private const val KEY_DATE = "usage_date"
        private const val KEY_ACTIVE = "active_package"
        private const val KEY_START_ELAPSED = "active_start_elapsed"
        private const val KEY_START_WALL = "active_start_wall"
        private const val KEY_START_BOOT = "active_start_boot"
        private const val CHANNEL_ID = "guardian_app_limits"
        private const val NOTIFICATION_ID = 612
        private const val DEFAULT_LIMIT_MS = 15L * 60L * 1000L
        private val DEFAULT_PACKAGES = setOf("com.instagram.android", "com.snapchat.android")
    }
}
