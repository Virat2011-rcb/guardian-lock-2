package com.morningsearch.guard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.morningsearch.guard.data.EscalationPlan
import org.json.JSONArray
import org.json.JSONObject

class GuardStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("guard_state", Context.MODE_PRIVATE)

    var commitmentStartedAt: Long
        get() = preferences.getLong("commitment_started_at", 0L)
        set(value) = preferences.edit().putLong("commitment_started_at", value).commit().let { }

    var lockUntilWall: Long
        get() = preferences.getLong("lock_until_wall", 0L)
        private set(value) = preferences.edit().putLong("lock_until_wall", value).commit().let { }

    var pendingActivateAt: Long
        get() = preferences.getLong("pending_activate_at", 0L)
        private set(value) = preferences.edit().putLong("pending_activate_at", value).commit().let { }

    val lockIncludesEntertainment: Boolean
        get() = preferences.getBoolean("lock_entertainment", false)

    val selectedEntertainmentPackages: Set<String>
        get() = preferences.getStringSet("selected_entertainment", emptySet())?.toSet().orEmpty()

    val isPending: Boolean get() = pendingActivateAt > 0L
    val isLocked: Boolean get() = effectiveRemainingMs() > 0L

    val commitmentEndsAt: Long
        get() = commitmentStartedAt.takeIf { it > 0L }
            ?.plus(GuardConfig.COMMITMENT_DURATION_MS) ?: 0L

    val commitmentActive: Boolean get() = commitmentEndsAt > System.currentTimeMillis()

    var tamperScore: Int
        get() = preferences.getInt("tamper_score", 0)
        private set(value) = preferences.edit().putInt("tamper_score", value.coerceAtLeast(0)).commit().let { }

    var accessibilityMissingSince: Long
        get() = preferences.getLong("accessibility_missing_since", 0L)
        set(value) = preferences.edit().putLong("accessibility_missing_since", value).commit().let { }

    var accessibilityPenaltyApplied: Boolean
        get() = preferences.getBoolean("accessibility_penalty_applied", false)
        set(value) = preferences.edit().putBoolean("accessibility_penalty_applied", value).commit().let { }

    val studyModeActive: Boolean get() = effectiveStudyRemainingMs() > 0L
    val studyModeSessionId: Long get() = preferences.getLong("study_session_id", 0L)
    val studyAllowedPackages: Set<String>
        get() = preferences.getStringSet("study_allowed_packages", emptySet())?.toSet().orEmpty()
    val studyInterruptedCount: Int get() = preferences.getInt("study_interruptions", 0)
    val studyEndsAt: Long get() = preferences.getLong("study_until_wall", 0L)
    val lostModeActive: Boolean get() = preferences.getBoolean("recover_lost_mode", false)
    val lostModeMessage: String get() = preferences.getString("recover_lost_message", "") ?: ""
    val recoverAlarmActive: Boolean get() = preferences.getBoolean("recover_alarm_active", false)
    val recoverFlashlightActive: Boolean get() = preferences.getBoolean("recover_flashlight_active", false)
    val cctvMonitorActive: Boolean get() = preferences.getBoolean("cctv_monitor_active", false)
    val urgeId: Long get() = preferences.getLong("urge_id", 0L)
    val urgeStartAt: Long get() = preferences.getLong("urge_start_wall", 0L)
    val urgeEndAt: Long get() = preferences.getLong("urge_end_wall", 0L)
    val urgeDurationMinutes: Int get() = preferences.getInt("urge_duration_minutes", 0)
    val urgeDurationExactMinutes: Boolean get() = preferences.getBoolean("urge_duration_exact", false)
    val urgeStoredActive: Boolean get() = preferences.getBoolean("urge_active", false)
    val urgeActive: Boolean get() = urgeStoredActive && urgeRemainingMs() > 0L
    var dashboardPublicKeyBase64: String
        get() = preferences.getString("dashboard_public_key_base64", "") ?: ""
        set(value) = preferences.edit().putString("dashboard_public_key_base64", value).commit().let { }
    var watchPublicKeyBase64: String
        get() = preferences.getString("watch_public_key_base64", "") ?: ""
        set(value) = preferences.edit().putString("watch_public_key_base64", value.trim()).commit().let { }
    val watchPairingArmed: Boolean get() = preferences.getLong("watch_pairing_armed_until", 0L) > System.currentTimeMillis()
    val pendingWatchPublicKey: String get() = preferences.getString("pending_watch_public_key", "") ?: ""
    var recoveryUploadUrl: String
        get() = preferences.getString("recovery_upload_url", "") ?: ""
        set(value) = preferences.edit().putString("recovery_upload_url", value.trim()).commit().let { }
    var recoveryUploadToken: String
        get() = preferences.getString("recovery_upload_token", "") ?: ""
        set(value) = preferences.edit().putString("recovery_upload_token", value.trim()).commit().let { }
    var cloudTransportState: String
        get() = preferences.getString("cloud_transport_state", "offline") ?: "offline"
        set(value) = preferences.edit().putString("cloud_transport_state", value).apply().let { }
    var cloudTransportLastChange: Long
        get() = preferences.getLong("cloud_transport_last_change", 0L)
        set(value) = preferences.edit().putLong("cloud_transport_last_change", value).apply().let { }
    var lastRemoteReconcileAt: Long
        get() = preferences.getLong("last_remote_reconcile_at", 0L)
        set(value) = preferences.edit().putLong("last_remote_reconcile_at", value).apply().let { }
    val liveTrackingActive: Boolean get() = preferences.getBoolean("live_tracking_active", false)
    val liveTrackingStartedAt: Long get() = preferences.getLong("live_tracking_started_at", 0L)

    @Synchronized
    fun setLiveTrackingActive(active: Boolean) {
        preferences.edit()
            .putBoolean("live_tracking_active", active)
            .putLong("live_tracking_started_at", if (active) System.currentTimeMillis() else 0L)
            .commit()
    }
    val maintenanceModeActive: Boolean get() = maintenanceRemainingMs() > 0L
    val maintenanceEndsAt: Long get() = preferences.getLong("maintenance_until_wall", 0L)

    @Synchronized
    fun beginMaintenanceMode(durationMs: Long) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        preferences.edit()
            .putLong("maintenance_until_wall", nowWall + durationMs)
            .putLong("maintenance_until_elapsed", nowElapsed + durationMs)
            .putInt("maintenance_boot_count", bootCount())
            .commit()
    }

    @Synchronized
    fun clearMaintenanceMode() {
        preferences.edit()
            .putLong("maintenance_until_wall", 0L)
            .putLong("maintenance_until_elapsed", 0L)
            .putInt("maintenance_boot_count", -1)
            .commit()
    }

    fun maintenanceRemainingMs(): Long {
        val wallRemaining = maintenanceEndsAt - System.currentTimeMillis()
        val storedBoot = preferences.getInt("maintenance_boot_count", -1)
        if (storedBoot == bootCount()) {
            val elapsedRemaining = preferences.getLong("maintenance_until_elapsed", 0L) - SystemClock.elapsedRealtime()
            return maxOf(wallRemaining, elapsedRemaining).coerceAtLeast(0L)
        }
        return wallRemaining.coerceAtLeast(0L)
    }

    @Synchronized
    fun setLostMode(active: Boolean, message: String = lostModeMessage) {
        preferences.edit()
            .putBoolean("recover_lost_mode", active)
            .putString("recover_lost_message", message.take(300))
            .commit()
    }

    @Synchronized
    fun setRecoverAlarmActive(active: Boolean) {
        preferences.edit().putBoolean("recover_alarm_active", active).commit()
    }

    @Synchronized
    fun setRecoverFlashlightActive(active: Boolean) {
        preferences.edit().putBoolean("recover_flashlight_active", active).commit()
    }

    @Synchronized
    fun setCctvMonitorActive(active: Boolean) {
        preferences.edit().putBoolean("cctv_monitor_active", active).commit()
    }

    @Synchronized
    fun armWatchPairingWindow(durationMs: Long = 120_000L) {
        preferences.edit().putLong("watch_pairing_armed_until", System.currentTimeMillis() + durationMs).remove("pending_watch_public_key").commit()
    }

    @Synchronized
    fun setPendingWatchPublicKey(publicKey: String) {
        if (watchPairingArmed && publicKey.isNotBlank()) preferences.edit().putString("pending_watch_public_key", publicKey.trim()).commit()
    }

    @Synchronized
    fun clearPendingWatchPublicKey() {
        preferences.edit().remove("pending_watch_public_key").remove("watch_pairing_armed_until").commit()
    }

    @Synchronized
    fun beginUrge(durationMinutes: Int): Long {
        val nowWall = System.currentTimeMillis()
        val durationMs = durationMinutes.coerceIn(1, 24) * 60L * 60L * 1000L
        val id = preferences.getLong("urge_sequence", 0L) + 1L
        val endWall = nowWall + durationMs
        preferences.edit()
            .putLong("urge_sequence", id)
            .putLong("urge_id", id)
            .putLong("urge_start_wall", nowWall)
            .putLong("urge_end_wall", endWall)
            .putLong("urge_start_elapsed", SystemClock.elapsedRealtime())
            .putLong("urge_end_elapsed", SystemClock.elapsedRealtime() + durationMs)
            .putInt("urge_boot_count", bootCount())
            .putInt("urge_duration_minutes", durationMinutes.coerceIn(1, 24))
            .putBoolean("urge_duration_exact", false)
            .putBoolean("urge_active", true)
            .putLong("urge_last_wall", nowWall)
            .commit()
        val history = urgeHistory().toMutableList()
        history += UrgeHistoryEntry(id, nowWall, endWall, durationMinutes.coerceIn(1, 24), false)
        saveUrgeHistory(history)
        return id
    }

    @Synchronized
    fun beginUrgeExactMinutes(durationMinutes: Int): Long {
        val minutes = durationMinutes.coerceIn(30, 24 * 60)
        val nowWall = System.currentTimeMillis()
        val durationMs = minutes * 60_000L
        val id = preferences.getLong("urge_sequence", 0L) + 1L
        val endWall = nowWall + durationMs
        preferences.edit()
            .putLong("urge_sequence", id).putLong("urge_id", id)
            .putLong("urge_start_wall", nowWall).putLong("urge_end_wall", endWall)
            .putLong("urge_start_elapsed", SystemClock.elapsedRealtime())
            .putLong("urge_end_elapsed", SystemClock.elapsedRealtime() + durationMs)
            .putInt("urge_boot_count", bootCount()).putInt("urge_duration_minutes", minutes)
            .putBoolean("urge_duration_exact", true).putBoolean("urge_active", true)
            .putLong("urge_last_wall", nowWall).commit()
        val history = urgeHistory().toMutableList()
        history += UrgeHistoryEntry(id, nowWall, endWall, minutes, false, true)
        saveUrgeHistory(history)
        return id
    }

    fun urgeRemainingMs(): Long {
        val observedWall = System.currentTimeMillis()
        val lastWall = preferences.getLong("urge_last_wall", 0L)
        // A backwards clock change must not give the active Urge fresh time.
        val effectiveWall = maxOf(observedWall, lastWall)
        if (effectiveWall != lastWall) preferences.edit().putLong("urge_last_wall", effectiveWall).apply()
        val wallRemaining = urgeEndAt - effectiveWall
        if (!preferences.getBoolean("urge_active", false)) return 0L
        return if (preferences.getInt("urge_boot_count", -1) == bootCount()) {
            val elapsedRemaining = preferences.getLong("urge_end_elapsed", 0L) - SystemClock.elapsedRealtime()
            minOf(wallRemaining, elapsedRemaining).coerceAtLeast(0L)
        } else {
            wallRemaining.coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun completeUrge() {
        val id = urgeId
        if (id == 0L) return
        val updated = urgeHistory().map { if (it.id == id) it.copy(completed = true) else it }
        saveUrgeHistory(updated)
        preferences.edit().putBoolean("urge_active", false).commit()
    }

    fun urgeHistory(): List<UrgeHistoryEntry> {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val raw = preferences.getString("urge_history", "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val entry = UrgeHistoryEntry(
                        item.optLong("id"), item.optLong("startAt"), item.optLong("endAt"),
                        item.optInt("durationMinutes"), item.optBoolean("completed"), item.optBoolean("exactMinutes")
                    )
                    if (entry.startAt >= cutoff) add(entry)
                }
            }.sortedByDescending { it.startAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveUrgeHistory(entries: List<UrgeHistoryEntry>) {
        val json = JSONArray()
        entries.filter { it.startAt >= System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L }
            .sortedBy { it.startAt }.takeLast(100).forEach {
                json.put(JSONObject().put("id", it.id).put("startAt", it.startAt).put("endAt", it.endAt)
                    .put("durationMinutes", it.durationMinutes).put("completed", it.completed)
                    .put("exactMinutes", it.exactMinutes))
            }
        preferences.edit().putString("urge_history", json.toString()).commit()
    }

    @Synchronized
    fun rememberRecoverCommandNonce(nonce: String): Boolean {
        val trimmed = nonce.take(80)
        val existing = preferences.getStringSet("recover_command_nonces", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (trimmed in existing) return false
        existing += trimmed
        preferences.edit().putStringSet("recover_command_nonces", existing.toList().takeLast(100).toSet()).commit()
        return true
    }

    @Synchronized
    fun beginStudyMode(durationMs: Long, allowedPackages: Set<String>, sessionId: Long) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        preferences.edit()
            .putLong("study_until_wall", nowWall + durationMs)
            .putLong("study_until_elapsed", nowElapsed + durationMs)
            .putInt("study_boot_count", bootCount())
            .putLong("study_session_id", sessionId)
            .putStringSet("study_allowed_packages", allowedPackages)
            .putInt("study_interruptions", 0)
            .commit()
    }

    @Synchronized
    fun incrementStudyInterruption(): Int {
        val next = studyInterruptedCount + 1
        preferences.edit().putInt("study_interruptions", next).commit()
        return next
    }

    @Synchronized
    fun clearStudyMode() {
        preferences.edit()
            .putLong("study_until_wall", 0L)
            .putLong("study_until_elapsed", 0L)
            .putLong("study_session_id", 0L)
            .putStringSet("study_allowed_packages", emptySet())
            .putInt("study_interruptions", 0)
            .commit()
    }

    fun effectiveStudyRemainingMs(): Long {
        val wallRemaining = studyEndsAt - System.currentTimeMillis()
        val storedBoot = preferences.getInt("study_boot_count", -1)
        if (storedBoot == bootCount()) {
            val elapsedRemaining = preferences.getLong("study_until_elapsed", 0L) - SystemClock.elapsedRealtime()
            return maxOf(wallRemaining, elapsedRemaining).coerceAtLeast(0L)
        }
        return wallRemaining.coerceAtLeast(0L)
    }

    @Synchronized
    fun beginPendingLock(plan: EscalationPlan) {
        val now = System.currentTimeMillis()
        preferences.edit()
            .putLong("pending_activate_at", now + GuardConfig.URGE_DELAY_MS)
            .putLong("pending_duration", plan.durationMs)
            .putBoolean("pending_entertainment", plan.includeEntertainment)
            .commit()
    }

    @Synchronized
    fun activatePendingLock(): Boolean {
        val duration = preferences.getLong("pending_duration", 0L)
        if (duration <= 0L) return false
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        preferences.edit()
            .putLong("lock_until_wall", nowWall + duration)
            .putLong("lock_until_elapsed", nowElapsed + duration)
            .putInt("lock_boot_count", bootCount())
            .putBoolean("lock_entertainment", preferences.getBoolean("pending_entertainment", false))
            .putLong("pending_activate_at", 0L)
            .putLong("pending_duration", 0L)
            .putBoolean("pending_entertainment", false)
            .commit()
        return true
    }

    @Synchronized
    fun clearLock() {
        preferences.edit()
            .putLong("lock_until_wall", 0L)
            .putLong("lock_until_elapsed", 0L)
            .putBoolean("lock_entertainment", false)
            .commit()
    }

    @Synchronized
    fun beginImmediateLock(durationMs: Long, includeEntertainment: Boolean) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        preferences.edit()
            .putLong("lock_until_wall", nowWall + durationMs)
            .putLong("lock_until_elapsed", nowElapsed + durationMs)
            .putInt("lock_boot_count", bootCount())
            .putBoolean("lock_entertainment", includeEntertainment)
            .putLong("pending_activate_at", 0L)
            .putLong("pending_duration", 0L)
            .putBoolean("pending_entertainment", false)
            .commit()
    }

    @Synchronized
    fun endProtection() {
        preferences.edit()
            .putLong("commitment_started_at", 0L)
            .putLong("lock_until_wall", 0L)
            .putLong("lock_until_elapsed", 0L)
            .putBoolean("lock_entertainment", false)
            .putLong("pending_activate_at", 0L)
            .putLong("pending_duration", 0L)
            .putBoolean("pending_entertainment", false)
            .putString("trigger_times", "")
            .putInt("tamper_score", 0)
            .putLong("accessibility_missing_since", 0L)
            .putBoolean("accessibility_penalty_applied", false)
            .commit()
    }


    @Synchronized
    fun recordTriggerAndPlan(now: Long = System.currentTimeMillis()): EscalationPlan {
        val cutoff = now - GuardConfig.ESCALATION_WINDOW_MS
        val recent = preferences.getString("trigger_times", "")
            .orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { it >= cutoff }
        val plan = EscalationEngine.plan(recent.size)
        preferences.edit().putString("trigger_times", (recent + now).joinToString(",")).commit()
        return plan
    }

    fun updateEntertainmentSelection(packages: Set<String>) {
        preferences.edit().putStringSet("selected_entertainment", packages).commit()
    }

    @Synchronized
    fun addTamperScore(points: Int): Int {
        val updated = (tamperScore + points).coerceAtMost(999)
        tamperScore = updated
        return updated
    }

    @Synchronized
    fun resetAccessibilityGrace() {
        preferences.edit()
            .putLong("accessibility_missing_since", 0L)
            .putBoolean("accessibility_penalty_applied", false)
            .commit()
    }

    fun effectiveRemainingMs(): Long {
        val wallRemaining = lockUntilWall - System.currentTimeMillis()
        val storedBoot = preferences.getInt("lock_boot_count", -1)
        if (storedBoot == bootCount()) {
            val elapsedRemaining = preferences.getLong("lock_until_elapsed", 0L) - SystemClock.elapsedRealtime()
            return maxOf(wallRemaining, elapsedRemaining).coerceAtLeast(0L)
        }
        return wallRemaining.coerceAtLeast(0L)
    }

    fun checkpoint(): TimeCheckpoint = TimeCheckpoint(
        preferences.getLong("checkpoint_wall", 0L),
        preferences.getLong("checkpoint_elapsed", 0L),
        preferences.getInt("checkpoint_boot", -1)
    )

    fun saveCheckpoint() {
        preferences.edit()
            .putLong("checkpoint_wall", System.currentTimeMillis())
            .putLong("checkpoint_elapsed", SystemClock.elapsedRealtime())
            .putInt("checkpoint_boot", bootCount())
            .commit()
    }

    private fun bootCount(): Int = Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.BOOT_COUNT,
        -1
    )
}

data class TimeCheckpoint(val wall: Long, val elapsed: Long, val bootCount: Int)

data class UrgeHistoryEntry(
    val id: Long,
    val startAt: Long,
    val endAt: Long,
    val durationMinutes: Int,
    val completed: Boolean,
    val exactMinutes: Boolean = false
)
