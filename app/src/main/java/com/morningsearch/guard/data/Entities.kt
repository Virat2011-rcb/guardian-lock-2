package com.morningsearch.guard.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trigger_events", indices = [Index("detectedAt")])
data class TriggerEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detectedAt: Long,
    val sourcePackage: String,
    val category: String,
    val lockDurationMs: Long,
    val escalationLevel: Int
)

@Entity(tableName = "tamper_events", indices = [Index("detectedAt")])
data class TamperEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detectedAt: Long,
    val type: String,
    val detail: String
)

@Entity(tableName = "personal_reasons")
data class PersonalReasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "keywords",
    indices = [Index(value = ["normalized"], unique = true), Index("language")]
)
data class KeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalized: String,
    val category: String,
    val language: String,
    val fuzzy: Boolean = true,
    val enabled: Boolean = true,
    val packVersion: Int = 1
)

@Entity(tableName = "focus_sessions", indices = [Index("startedAt"), Index("endedAt")])
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val plannedEndAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long,
    val completed: Boolean = false,
    val interruptions: Int = 0,
    val allowedPackages: String
)

@Entity(tableName = "recover_events", indices = [Index("createdAt"), Index("type")])
data class RecoverEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val type: String,
    val detail: String
)

@Entity(tableName = "device_status_snapshots", indices = [Index("capturedAt")])
data class DeviceStatusSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAt: Long = System.currentTimeMillis(),
    val batteryPercent: Int,
    val charging: Boolean,
    val networkSummary: String,
    val locationSummary: String,
    val lostModeActive: Boolean,
    val locked: Boolean,
    val simSummary: String,
    val ipAddress: String
)
