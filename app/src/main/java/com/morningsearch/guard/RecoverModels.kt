package com.morningsearch.guard

data class RecoverStatus(
    val batteryPercent: Int,
    val charging: Boolean,
    val networkSummary: String,
    val locationSummary: String,
    val lostModeActive: Boolean,
    val locked: Boolean,
    val simSummary: String,
    val ipAddress: String
)

sealed class RecoverCommandResult {
    data object Applied : RecoverCommandResult()
    data class Unsupported(val reason: String) : RecoverCommandResult()
    data class Failed(val reason: String) : RecoverCommandResult()
}
