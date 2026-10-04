package com.morningsearch.guardianwatch

import android.content.Context

class WatchStatusRepository(private val context: Context) {
    private val store = WatchStateStore(context)

    fun refresh(): WatchStatus {
        if (WatchDataLayerClient(context).requestStatus()) {
            Thread.sleep(600)
            val nearby = store.lastStatus()
            if (nearby.capturedAt > 0L) return nearby.copy(transport = "Nearby Phone")
        }
        return WatchCloudClient(context, store).latestStatus()
    }
}
