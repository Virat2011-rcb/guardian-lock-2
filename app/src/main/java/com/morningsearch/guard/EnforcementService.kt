package com.morningsearch.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.morningsearch.guard.data.LocalAuditRepository

class EnforcementService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audit: LocalAuditRepository
    private lateinit var dashboardServer: DashboardCommandServer
    private var lastAccessibilityState: Boolean? = null
    private var lastDeviceOwnerState: Boolean? = null

    private val check = object : Runnable {
        override fun run() {
            LockManager(this@EnforcementService).reconcile()
            GuardianRecoverManager(this@EnforcementService).reconcile()
            TimeIntegrityMonitor(this@EnforcementService).checkAndCheckpoint()
            RecoverUploadManager(this@EnforcementService).pollRemoteCommandsAsync()
            inspectRequiredState()
            saveHeartbeat()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audit = LocalAuditRepository(this)
        dashboardServer = DashboardCommandServer(this)
        inspectPreviousHeartbeat()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Protection monitor is active"))
        dashboardServer.start()
        scheduleSelfHeal()
        handler.post(check)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONCILE) LockManager(this).reconcile()
        scheduleSelfHeal()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        dashboardServer.stop()
        scheduleSelfHeal()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleSelfHeal()
        TamperManager(this).record("task_removed", "Guardian Lock was cleared from recents", 10)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun inspectRequiredState() {
        val store = GuardStore(this)
        if (store.commitmentStartedAt == 0L) return
        val accessibility = isAccessibilityEnabled()
        val now = System.currentTimeMillis()
        val manager = LockManager(this)
        if (accessibility) {
            store.resetAccessibilityGrace()
            manager.enforceAccessibilityState(true)
        } else {
            if (store.accessibilityMissingSince == 0L) {
                store.accessibilityMissingSince = now
                TamperManager(this).record("accessibility_lost", "Accessibility service is not active", 10)
            }
            val missingFor = now - store.accessibilityMissingSince
            if (missingFor >= GuardConfig.ACCESSIBILITY_GRACE_MS) {
                manager.enforceAccessibilityState(false)
                if (!store.accessibilityPenaltyApplied) {
                    store.accessibilityPenaltyApplied = true
                    TamperManager(this).record("accessibility_fail_closed", "Browsers suspended after grace period", 25)
                }
            }
        }
        lastAccessibilityState = accessibility
        val deviceOwner = manager.isDeviceOwner
        if (lastDeviceOwnerState == true && !deviceOwner) {
            TamperManager(this).record("device_owner_missing", "Device Owner protection is no longer active", 50)
        }
        lastDeviceOwnerState = deviceOwner
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(if (accessibility) "Protection monitor is active" else "Action required: Accessibility must be re-enabled")
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, SearchGuardAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }

    private fun inspectPreviousHeartbeat() {
        val prefs = getSharedPreferences("watchdog", MODE_PRIVATE)
        val previous = prefs.getLong("heartbeat_wall", 0L)
        val previousBoot = prefs.getInt("heartbeat_boot", -1)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val gap = System.currentTimeMillis() - previous
        if (previous > 0L && previousBoot == boot && gap > 30L * 60L * 1000L) {
            audit.logTamper("watchdog_gap", "Protection process was unavailable for approximately $gap ms")
        }
    }

    private fun saveHeartbeat() {
        getSharedPreferences("watchdog", MODE_PRIVATE).edit()
            .putLong("heartbeat_wall", System.currentTimeMillis())
            .putInt("heartbeat_boot", Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1))
            .apply()
    }

    private fun scheduleSelfHeal() {
        val alarm = getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            2002,
            Intent(this, ServiceWatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + SELF_HEAL_INTERVAL_MS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Guardian Lock")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_RECONCILE = "com.morningsearch.guard.RECONCILE"
        private const val CHANNEL_ID = "guardian_enforcement"
        private const val NOTIFICATION_ID = 401
        private const val CHECK_INTERVAL_MS = 10_000L
        private const val SELF_HEAL_INTERVAL_MS = 60_000L

        fun start(context: Context, action: String = ACTION_RECONCILE) {
            runCatching { ContextCompat.startForegroundService(
                context,
                Intent(context, EnforcementService::class.java).setAction(action)
            ) }
        }
    }
}
