package com.morningsearch.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.util.Calendar
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic browser-only prediction from Guardian's own seven-day Urge history.
 * At least three events must fall within a 45-minute time-of-day cluster. The
 * cluster median is the start, and the median selected duration is the window length.
 */
class UrgePredictionManager(private val context: Context) {
    private val store = GuardStore(context)
    private val prefs = context.getSharedPreferences("urge_prediction", Context.MODE_PRIVATE)

    fun prediction(): UrgePrediction? {
        val entries = store.urgeHistory()
        if (entries.size < MIN_EVENTS) return null
        val points = entries.map { UrgePoint(localMinute(it.startAt), if (it.exactMinutes) it.durationMinutes else it.durationMinutes * 60) }
        var best = emptyList<UrgePoint>()
        var bestSeed = 0
        points.forEach { seed ->
            val cluster = points.filter { circularDistance(seed.minute, it.minute) <= CLUSTER_RADIUS_MINUTES }
            if (cluster.size > best.size || (cluster.size == best.size && seed.minute < bestSeed)) {
                best = cluster
                bestSeed = seed.minute
            }
        }
        if (best.size < MIN_EVENTS) return null
        val adjusted = best.map { offsetFromSeed(it.minute, bestSeed) }.sorted()
        val center = ((bestSeed + adjusted[adjusted.size / 2]) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val durations = best.map { it.durationMinutes }.sorted()
        val durationMinutes = durations[durations.size / 2].coerceIn(1, 24 * 60)
        return UrgePrediction(center, durationMinutes, best.size, System.currentTimeMillis())
    }

    /** Reconciles notification state and returns whether the browser-only window is active now. */
    fun reconcile(): Boolean {
        val current = prediction()
        val active = current?.isActiveNow() == true
        val previous = prefs.getBoolean(KEY_ACTIVE, false)
        if (active && !previous) notifyStarted()
        if (!active && previous) notifyEnded()
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
        current?.let {
            prefs.edit().putInt(KEY_CENTER, it.centerMinute).putInt(KEY_DURATION, it.durationMinutes).putInt(KEY_COUNT, it.matchingEvents).apply()
        }
        return active
    }

    fun description(): String {
        val current = prediction() ?: return "Predicted Urge Protection: no schedule yet (need at least 3 matching urges in 7 days)."
        val start = formatMinute(current.centerMinute)
        val end = formatMinute((current.centerMinute + current.durationMinutes) % MINUTES_PER_DAY)
        return "Predicted Urge Protection (Browser only)\n" +
            "High-risk time: $start – $end\n" +
            "Expected duration: ${current.durationMinutes} minutes\n" +
            "Based on: ${current.matchingEvents} urges in the last 7 days\n" +
            "Last updated: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.calculatedAt))}\n" +
            "Status: ${if (current.isActiveNow()) "Browser protection ACTIVE" else "inactive"}"
    }

    private fun notifyStarted() {
        notificationManager().notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Predicted Urge Protection")
            .setContentText("Browser access is temporarily blocked based on your recent urge pattern.")
            .setOngoing(true).build())
    }

    private fun notifyEnded() {
        notificationManager().notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("Predicted Urge Protection ended")
            .setContentText("Browser access restored.").setOngoing(false).build())
    }

    private fun notificationManager(): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Predicted browser protection", NotificationManager.IMPORTANCE_LOW))
        return manager
    }

    private fun localMinute(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }
        .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun circularDistance(a: Int, b: Int): Int {
        val difference = abs(a - b)
        return minOf(difference, MINUTES_PER_DAY - difference)
    }

    private fun offsetFromSeed(value: Int, seed: Int): Int {
        var offset = value - seed
        if (offset > MINUTES_PER_DAY / 2) offset -= MINUTES_PER_DAY
        if (offset < -MINUTES_PER_DAY / 2) offset += MINUTES_PER_DAY
        return offset
    }

    private fun formatMinute(minute: Int): String {
        val hour = (minute / 60) % 24
        val am = if (hour < 12) "AM" else "PM"
        val shown = when (val h = hour % 12) { 0 -> 12; else -> h }
        return "%d:%02d %s".format(Locale.US, shown, minute % 60, am)
    }

    companion object {
        private const val MIN_EVENTS = 3
        private const val CLUSTER_RADIUS_MINUTES = 45
        private const val MINUTES_PER_DAY = 24 * 60
        private const val CHANNEL_ID = "guardian_predicted_urge"
        private const val NOTIFICATION_ID = 614
        private const val KEY_ACTIVE = "active"
        private const val KEY_CENTER = "center"
        private const val KEY_DURATION = "duration"
        private const val KEY_COUNT = "count"
    }
}

data class UrgePrediction(
    val centerMinute: Int,
    val durationMinutes: Int,
    val matchingEvents: Int,
    val calculatedAt: Long
) {
    fun isActiveNow(): Boolean {
        if (durationMinutes >= 24 * 60) return true
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val end = (centerMinute + durationMinutes) % (24 * 60)
        return if (centerMinute + durationMinutes < 24 * 60) {
            minute in centerMinute until (centerMinute + durationMinutes)
        } else {
            minute >= centerMinute || minute < end
        }
    }
}

private data class UrgePoint(val minute: Int, val durationMinutes: Int)
