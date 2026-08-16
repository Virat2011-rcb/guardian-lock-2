package com.morningsearch.guard

import android.content.Context
import com.morningsearch.guard.data.DeviceStatusSnapshotEntity
import com.morningsearch.guard.data.GuardianDatabase
import com.morningsearch.guard.data.RecoverEventEntity
import kotlinx.coroutines.runBlocking

class RecoverTimeline(context: Context) {
    private val dao = GuardianDatabase.get(context).guardianDao()

    fun record(type: String, detail: String) {
        runCatching {
            runBlocking {
                dao.insertRecoverEvent(RecoverEventEntity(type = type, detail = detail.take(600)))
            }
        }
    }

    fun recordStatus(status: RecoverStatus) {
        runCatching {
            runBlocking {
                dao.insertDeviceStatusSnapshot(
                    DeviceStatusSnapshotEntity(
                        batteryPercent = status.batteryPercent,
                        charging = status.charging,
                        networkSummary = status.networkSummary,
                        locationSummary = status.locationSummary,
                        lostModeActive = status.lostModeActive,
                        locked = status.locked,
                        simSummary = status.simSummary,
                        ipAddress = status.ipAddress
                    )
                )
            }
        }
    }
}
