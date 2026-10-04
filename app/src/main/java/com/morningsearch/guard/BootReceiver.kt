package com.morningsearch.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (context.packageManager.isSafeMode) {
            TamperManager(context).record("safe_mode_boot", "Device booted in Safe Mode", 40)
        }
        when (intent?.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                TamperManager(context).record("time_setting_changed", "System time or timezone changed", 30)
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                TamperManager(context).record("app_updated", "Guardian Lock package was replaced", 5)
            "android.intent.action.SIM_STATE_CHANGED" -> {
                RecoverTimeline(context).record("sim_state_changed", "SIM state changed; local status snapshot captured")
                GuardianRecoverManager(context).refreshStatus()
            }
        }
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
        AppLimitManager(context).reconcilePolicy()
    }
}
