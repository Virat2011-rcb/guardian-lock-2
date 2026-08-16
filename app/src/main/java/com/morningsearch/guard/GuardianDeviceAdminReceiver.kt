package com.morningsearch.guard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.morningsearch.guard.data.LocalAuditRepository

class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        LocalAuditRepository(context).logTamper("device_admin_enabled", "Device administration enabled")
        EnforcementService.start(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        LocalAuditRepository(context).logTamper("device_admin_disable_requested", "A Device Admin removal was requested")
        return "Guardian authorization is required. Removing protection may end active recovery locks."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        LocalAuditRepository(context).logTamper("device_admin_disabled", "Device administration was disabled")
    }
}
