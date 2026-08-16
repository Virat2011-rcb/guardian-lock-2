package com.morningsearch.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PackageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val packageName = intent?.data?.schemeSpecificPart
        if (!packageName.isNullOrBlank() && BrowserRegistry(context).isBrowser(packageName)) {
            TamperManager(context).record("new_browser_installed", "A browser was installed or replaced: $packageName", 20)
            LockManager(context).suspendPackageIfDeviceOwner(packageName)
        }
        EnforcementService.start(context, EnforcementService.ACTION_RECONCILE)
    }
}
