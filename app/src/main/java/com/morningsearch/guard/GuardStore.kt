package com.morningsearch.guard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.morningsearch.guard.data.EscalationPlan

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
    var dashboardPublicKeyBase64: String
        get() = preferences.getString("dashboard_public_key_base64", "") ?: ""
        set(value) = preferences.edit().putString("dashboard_public_key_base64", value).commit().let { }
    var recoveryUploadUrl: String
        get() = preferences.getString("recovery_upload_url", "") ?: ""
        set(value) = preferences.edit().putString("recovery_upload_url", value.trim()).commit().let { }
    var recoveryUploadToken: String
        get() = preferences.getString("recovery_upload_token", "") ?: ""
        set(value) = preferences.edit().putString("recovery_upload_token", value.trim()).commit().let { }
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
