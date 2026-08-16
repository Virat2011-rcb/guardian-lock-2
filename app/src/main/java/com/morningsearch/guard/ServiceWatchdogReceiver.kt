package com.morningsearch.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LockManager(context).reconcile()
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
    }
}
