package com.morningsearch.guard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GuardianDao {
    @Insert suspend fun insertTrigger(event: TriggerEventEntity)
    @Insert suspend fun insertTamper(event: TamperEventEntity)
    @Insert suspend fun insertReason(reason: PersonalReasonEntity): Long
    @Insert suspend fun insertFocusSession(session: FocusSessionEntity): Long
    @Insert suspend fun insertRecoverEvent(event: RecoverEventEntity): Long
    @Insert suspend fun insertDeviceStatusSnapshot(snapshot: DeviceStatusSnapshotEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertKeywords(items: List<KeywordEntity>)

    @Query("SELECT * FROM keywords WHERE enabled = 1")
    suspend fun enabledKeywords(): List<KeywordEntity>

    @Query("SELECT * FROM personal_reasons WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun enabledReasons(): List<PersonalReasonEntity>

    @Query("SELECT COUNT(*) FROM trigger_events WHERE detectedAt >= :since")
    suspend fun triggerCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM trigger_events")
    suspend fun totalTriggerCount(): Int

    @Query("SELECT * FROM trigger_events WHERE detectedAt >= :since ORDER BY detectedAt")
    suspend fun triggersSince(since: Long): List<TriggerEventEntity>

    @Query("SELECT * FROM tamper_events ORDER BY detectedAt DESC LIMIT :limit")
    suspend fun recentTamperEvents(limit: Int = 100): List<TamperEventEntity>

    @Query("SELECT MAX(detectedAt) FROM trigger_events")
    suspend fun lastTriggerAt(): Long?

    @Query("UPDATE focus_sessions SET endedAt = :endedAt, completed = :completed, interruptions = :interruptions WHERE id = :id")
    suspend fun finishFocusSession(id: Long, endedAt: Long, completed: Boolean, interruptions: Int)

    @Query("SELECT COALESCE(SUM(CASE WHEN endedAt IS NOT NULL THEN MIN(durationMs, endedAt - startedAt) ELSE 0 END), 0) FROM focus_sessions WHERE startedAt >= :since")
    suspend fun completedFocusMsSince(since: Long): Long

    @Query("SELECT COALESCE(SUM(CASE WHEN endedAt IS NOT NULL THEN MIN(durationMs, endedAt - startedAt) ELSE 0 END), 0) FROM focus_sessions")
    suspend fun totalCompletedFocusMs(): Long

    @Query("SELECT * FROM recover_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentRecoverEvents(limit: Int = 50): List<RecoverEventEntity>

    @Query("SELECT * FROM device_status_snapshots ORDER BY capturedAt DESC LIMIT 1")
    suspend fun latestDeviceStatusSnapshot(): DeviceStatusSnapshotEntity?
}
