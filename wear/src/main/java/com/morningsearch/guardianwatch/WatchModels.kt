package com.morningsearch.guardianwatch

data class WatchStatus(
    val phoneOnline: Boolean = false,
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val network: String = "unknown",
    val location: String = "",
    val lostMode: Boolean = false,
    val studyMode: Boolean = false,
    val maintenanceMode: Boolean = false,
    val cctvMonitor: Boolean = false,
    val capturedAt: Long = 0L,
    val transport: String = "Unknown"
)

data class WatchRecoveryEvent(
    val id: String = "",
    val type: String = "",
    val detail: String = "",
    val timestamp: Long = 0L
)

data class SignedWatchCommand(
    val command: String,
    val payload: String,
    val nonce: String,
    val issuedAt: Long,
    val signatureBase64: String
)
