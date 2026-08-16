package com.morningsearch.guard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.abs

class TimeIntegrityMonitor(private val context: Context) {
    private val store = GuardStore(context)

    fun checkAndCheckpoint() {
        val previous = store.checkpoint()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (previous.wall > 0L && previous.bootCount == boot) {
            val wallDelta = System.currentTimeMillis() - previous.wall
            val elapsedDelta = SystemClock.elapsedRealtime() - previous.elapsed
            if (abs(wallDelta - elapsedDelta) > 120_000L) {
                TamperManager(context).record(
                    "time_change",
                    "Wall clock drifted from monotonic time by ${wallDelta - elapsedDelta} ms",
                    30
                )
            }
        }
        store.saveCheckpoint()
    }
}
