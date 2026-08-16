package com.morningsearch.guard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Builds an on-device list of apps that may be used during a voluntary focus session. */
class WhitelistManager(private val context: Context) {
    private val packageManager = context.packageManager

    fun defaultAllowedPackages(includeCamera: Boolean, includeCalculator: Boolean): Set<String> {
        val result = mutableSetOf(context.packageName)
        result += essentialSystemPackages()
        findLaunchablePackage("android.intent.action.DIAL")?.let(result::add)
        findLaunchablePackage("android.intent.action.SHOW_ALARMS")?.let(result::add)
        if (includeCamera) findLaunchablePackage("android.media.action.IMAGE_CAPTURE")?.let(result::add)
        if (includeCalculator) {
            packageManager.queryIntentActivities(Intent("android.intent.action.MAIN").addCategory("android.intent.category.APP_CALCULATOR"), 0)
                .mapTo(result) { it.activityInfo.packageName }
        }
        return result
    }

    fun launchableThirdPartyPackages(): List<AppChoice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { info ->
                AppChoice(info.activityInfo.packageName, info.loadLabel(packageManager).toString())
            }.distinctBy { it.packageName }.filter { it.packageName != context.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun findLaunchablePackage(action: String): String? = packageManager
        .resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

    private fun essentialSystemPackages(): Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.dialer",
        "com.miui.securitycenter"
    )
}

data class AppChoice(val packageName: String, val label: String)
