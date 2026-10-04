package com.morningsearch.guard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

class GuardianRecoverManager(private val context: Context) {
    private val store = GuardStore(context)
    private val timeline = RecoverTimeline(context)
    private val policy = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, GuardianDeviceAdminReceiver::class.java)
    private val isDeviceOwner: Boolean get() = policy.isDeviceOwnerApp(context.packageName)

    fun refreshStatus(): RecoverStatus {
        val status = RecoverDeviceStatus(context).capture()
        timeline.recordStatus(status)
        timeline.record("status_refreshed", "Device status captured locally")
        return status
    }

    fun applySignedCommand(command: SignedRecoverCommand): RecoverCommandResult {
        if (!RecoverCommandVerifier(context).verify(command)) {
            timeline.record("signed_command_rejected", "Rejected ${command.command}: unsigned, expired, invalid, or replayed")
            return RecoverCommandResult.Failed("Signed command rejected")
        }
        timeline.record("signed_command_accepted", command.command)
        return when (command.command) {
            "lock" -> lockDevice(customPinRequested = false)
            "lost_mode_on" -> enterLostMode(command.payload)
            "found_device" -> foundDevice()
            "alarm_on" -> startAlarm()
            "alarm_off" -> stopAlarm()
            "flashlight_on" -> startFlashlight()
            "flashlight_off" -> stopFlashlight()
            "cctv_on" -> startCctvMonitor(fromSignedCommand = true)
            "cctv_off" -> stopCctvMonitor()
            "live_tracking_start" -> startLiveTracking()
            "live_tracking_stop" -> stopLiveTracking()
            "urge_start" -> {
                val minutes = command.payload.toIntOrNull() ?: return RecoverCommandResult.Failed("Invalid Urge duration")
                if (UrgeManager(context).startMinutes(minutes)) RecoverCommandResult.Applied else RecoverCommandResult.Failed("Urge could not start")
            }
            "maintenance_on" -> if (LockManager(context).beginMaintenanceMode(true)) {
                RecoverCommandResult.Applied
            } else {
                RecoverCommandResult.Failed("Maintenance Mode failed")
            }
            "status", "locate_now" -> {
                RecoverUploadManager(context).uploadStatusAsync(refreshStatus())
                RecoverCommandResult.Applied
            }
            "capture_front", "capture_rear", "record_audio" -> requestCapture(command.command, fromSignedCommand = true)
            else -> RecoverCommandResult.Unsupported("Unknown recover command: ${command.command}")
        }
    }

    fun lockDevice(customPinRequested: Boolean = false): RecoverCommandResult {
        if (!isDeviceOwner) return RecoverCommandResult.Failed("Device Owner is not active")
        return runCatching {
            policy.lockNow()
            if (customPinRequested) {
                timeline.record("lock_pin_unsupported", "Android 11+ does not allow setting a custom device PIN from this app without a pre-enrolled reset token")
                RecoverCommandResult.Unsupported("Custom PIN change is not supported on this provisioned Android version; device was locked with lockNow.")
            } else {
                timeline.record("device_locked", "Device locked by Guardian Recover")
                RecoverCommandResult.Applied
            }
        }.getOrElse {
            timeline.record("device_lock_failed", it.javaClass.simpleName)
            RecoverCommandResult.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    fun enterLostMode(message: String): RecoverCommandResult {
        if (!isDeviceOwner) return RecoverCommandResult.Failed("Device Owner is not active")
        store.setLostMode(true, message.ifBlank { "This phone is protected by Guardian Lock. Please contact the owner." })
        runCatching { policy.setLockTaskPackages(admin, lostModeLockTaskPackages()) }
        runCatching { policy.setStatusBarDisabled(admin, true) }
        policy.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
        policy.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        policy.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        policy.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
        policy.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        timeline.record("lost_mode_on", "Lost Mode enabled")
        RecoverUploadManager(context).uploadStatusAsync(refreshStatus())
        context.startActivity(
            Intent(context, LostModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        context.startActivity(
            Intent(context, RecoveryCaptureActivity::class.java)
                .putExtra(RecoveryCaptureActivity.EXTRA_MODE, RecoveryCaptureActivity.MODE_FRONT)
                .putExtra(RecoveryCaptureActivity.EXTRA_NEXT_MODE, RecoveryCaptureActivity.MODE_AUDIO)
                .putExtra(RecoveryCaptureActivity.EXTRA_SECONDS, 20)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        return RecoverCommandResult.Applied
    }

    fun foundDevice(): RecoverCommandResult {
        if (!isDeviceOwner) return RecoverCommandResult.Failed("Device Owner is not active")
        RecoverAlarmController(context).stop()
        RecoverFlashlightController(context).stop()
        store.setLostMode(false)
        runCatching { policy.setStatusBarDisabled(admin, false) }
        runCatching { policy.setLockTaskPackages(admin, emptyArray<String>()) }
        policy.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
        policy.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        policy.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        policy.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
        policy.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        timeline.record("found_device", "Lost Mode, alarm, and flashlight were stopped")
        return RecoverCommandResult.Applied
    }

    fun startAlarm(): RecoverCommandResult {
        val ok = RecoverAlarmController(context).start()
        timeline.record(if (ok) "alarm_started" else "alarm_failed", if (ok) "Siren started" else "No alarm ringtone available")
        return if (ok) RecoverCommandResult.Applied else RecoverCommandResult.Failed("No alarm ringtone available")
    }

    fun stopAlarm(): RecoverCommandResult {
        RecoverAlarmController(context).stop()
        timeline.record("alarm_stopped", "Siren stopped")
        return RecoverCommandResult.Applied
    }

    fun startFlashlight(): RecoverCommandResult {
        val ok = RecoverFlashlightController(context).start()
        timeline.record(if (ok) "flashlight_started" else "flashlight_failed", if (ok) "Flashlight blink started" else "No torch-capable camera found")
        return if (ok) RecoverCommandResult.Applied else RecoverCommandResult.Failed("No torch-capable camera found")
    }

    fun stopFlashlight(): RecoverCommandResult {
        RecoverFlashlightController(context).stop()
        timeline.record("flashlight_stopped", "Flashlight stopped")
        return RecoverCommandResult.Applied
    }

    fun requestCapture(kind: String, fromSignedCommand: Boolean = false): RecoverCommandResult {
        val mode = when (kind) {
            "capture_front" -> RecoveryCaptureActivity.MODE_FRONT
            "capture_rear" -> RecoveryCaptureActivity.MODE_REAR
            "record_audio" -> RecoveryCaptureActivity.MODE_AUDIO
            else -> return RecoverCommandResult.Unsupported("Unknown capture command: $kind")
        }
        timeline.record(
            "capture_requested",
            "$kind requested; visible recovery capture screen opened${if (fromSignedCommand) " by signed command" else ""}"
        )
        if (kind == "record_audio" || kind == "capture_front" || kind == "capture_rear") {
            RecoverUploadManager(context).uploadStatusAsync()
        }
        context.startActivity(
            Intent(context, RecoveryCaptureActivity::class.java)
                .putExtra(RecoveryCaptureActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        return RecoverCommandResult.Applied
    }

    fun startCctvMonitor(fromSignedCommand: Boolean = false): RecoverCommandResult {
        timeline.record(
            "cctv_monitor_requested",
            "Visible CCTV Monitor Mode requested${if (fromSignedCommand) " by signed command" else ""}"
        )
        RecoverUploadManager(context).uploadStatusAsync()
        context.startActivity(
            Intent(context, CctvMonitorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        return RecoverCommandResult.Applied
    }

    fun stopCctvMonitor(): RecoverCommandResult {
        store.setCctvMonitorActive(false)
        timeline.record("cctv_monitor_stop_requested", "Visible CCTV Monitor Mode stop requested")
        RecoverUploadManager(context).uploadEventAsync("cctv_monitor_stop_requested", "Visible CCTV Monitor Mode stop requested")
        return RecoverCommandResult.Applied
    }

    fun startLiveTracking(): RecoverCommandResult {
        if (!isDeviceOwner) return RecoverCommandResult.Failed("Device Owner is not active")
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return RecoverCommandResult.Failed("Location permission is not granted")
        store.setLiveTrackingActive(true)
        timeline.record("live_tracking_started", "Owner-authorized live location session started")
        LiveLocationService.start(context)
        return RecoverCommandResult.Applied
    }

    fun stopLiveTracking(): RecoverCommandResult {
        store.setLiveTrackingActive(false)
        LiveLocationService.stop(context)
        timeline.record("live_tracking_stopped", "Owner-authorized live location session stopped")
        RecoverUploadManager(context).uploadEventAsync("live_tracking_stopped", "Live location session stopped")
        return RecoverCommandResult.Applied
    }

    fun reconcile() {
        if (store.lostModeActive) {
            runCatching { policy.setLockTaskPackages(admin, lostModeLockTaskPackages()) }
            runCatching { policy.setStatusBarDisabled(admin, true) }
            policy.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
            policy.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            policy.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            policy.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
            policy.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        }
        if (store.recoverAlarmActive) RecoverAlarmController(context).start()
        if (store.recoverFlashlightActive) RecoverFlashlightController(context).start()
    }

    private fun lostModeLockTaskPackages(): Array<String> {
        val dialer = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL), 0)?.activityInfo?.packageName
        }.getOrNull()
        return (listOf(context.packageName) + listOfNotNull(dialer)).distinct().toTypedArray()
    }
}
