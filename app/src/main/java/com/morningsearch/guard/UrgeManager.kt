package com.morningsearch.guard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

/** Owns the voluntary manual Urge Lock lifecycle; it never changes Device Owner state. */
class UrgeManager(private val context: Context) {
    private val store = GuardStore(context)

    fun start(durationHours: Int): Boolean {
        return startInternal(durationHours.coerceIn(1, 24))
    }

    fun startMinutes(durationMinutes: Int): Boolean {
        if (!LockManager(context).isDeviceOwner || store.urgeActive) return false
        store.beginUrgeExactMinutes(durationMinutes)
        RecoverTimeline(context).record("URGE_STARTED", "${durationMinutes.coerceIn(30, 24 * 60)} minute Urge Lock")
        scheduleExpiry(store.urgeEndAt)
        LockManager(context).reconcile()
        notifyActive()
        return true
    }

    private fun startInternal(durationHours: Int): Boolean {
        if (!LockManager(context).isDeviceOwner || store.urgeActive) return false
        store.beginUrge(durationHours)
        RecoverTimeline(context).record("URGE_STARTED", "${durationHours.coerceIn(1, 24)} hour Urge Lock")
        scheduleExpiry(store.urgeEndAt)
        LockManager(context).reconcile()
        notifyActive()
        return true
    }

    fun reconcile(): Boolean {
        if (store.urgeStoredActive && store.urgeId != 0L && store.urgeEndAt > 0L && store.urgeRemainingMs() <= 0L) {
            store.completeUrge()
            RecoverTimeline(context).record("URGE_EXPIRED", "Urge Lock completed")
            notifyEndedOnce()
            return true
        }
        if (store.urgeActive) notifyActive()
        return false
    }

    fun tick() {
        val expired = reconcile()
        if (expired) LockManager(context).reconcile()
    }

    fun remainingMs() = store.urgeRemainingMs()

    private fun scheduleExpiry(at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 3011, Intent(context, UnlockReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun notifyActive() {
        val remaining = store.urgeRemainingMs()
        if (remaining <= 0L) return
        val manager = notificationManager()
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("URGE LOCK ACTIVE")
            .setContentText("Browser access blocked • Settings + Hotspot restricted • ${format(remaining)} remaining")
            .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS).build())
    }

    private fun notifyEndedOnce() {
        val prefs = context.getSharedPreferences("urge_notifications", Context.MODE_PRIVATE)
        if (prefs.getLong("last_ended_id", 0L) == store.urgeId) return
        prefs.edit().putLong("last_ended_id", store.urgeId).apply()
        val reflection = PendingIntent.getActivity(
            context, 711, Intent(context, UrgeReflectionActivity::class.java)
                .putExtra(UrgeReflectionActivity.EXTRA_URGE_ID, store.urgeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationManager().notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Urge Lock ended")
            .setContentText("Your selected ${if (store.urgeDurationExactMinutes) "${store.urgeDurationMinutes}-minute" else "${store.urgeDurationMinutes}-hour"} period is complete.")
            .setContentIntent(reflection).setAutoCancel(true).setOngoing(false).build())
    }

    private fun notificationManager(): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Urge Lock", NotificationManager.IMPORTANCE_LOW))
        return manager
    }

    private fun format(ms: Long) = "%02dh %02dm".format(Locale.US, ms / 3_600_000L, (ms / 60_000L) % 60L)

    companion object {
        private const val CHANNEL_ID = "guardian_urge"
        private const val NOTIFICATION_ID = 613
    }
}
