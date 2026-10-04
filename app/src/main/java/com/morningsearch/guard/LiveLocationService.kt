package com.morningsearch.guard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/** Visible, explicitly started location stream. It never runs outside a signed session. */
class LiveLocationService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification("Live Tracking is active"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification("Live Tracking is active"))
        }
        client = LocationServices.getFusedLocationProviderClient(this)
        if (!hasLocationPermission()) {
            GuardStore(this).setLiveTrackingActive(false)
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMaxUpdateDelayMillis(8_000L)
            .setWaitForAccurateLocation(false)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.lastOrNull()?.let { RecoverUploadManager(this@LiveLocationService).uploadLiveLocation(it) }
            }
        }
        runCatching { client.requestLocationUpdates(request, callback!!, mainLooper) }
            .onFailure {
                RecoverTimeline(this).record("live_tracking_failed", it.message ?: it.javaClass.simpleName)
                GuardStore(this).setLiveTrackingActive(false)
                stopSelf()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GuardStore(this).liveTrackingActive) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { runCatching { client.removeLocationUpdates(it) } }
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Guardian Lock")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live Tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "guardian_live_location"
        private const val NOTIFICATION_ID = 402
        const val ACTION_START = "com.morningsearch.guard.LIVE_TRACKING_START"
        const val ACTION_STOP = "com.morningsearch.guard.LIVE_TRACKING_STOP"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java).setAction(ACTION_START)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveLocationService::class.java).setAction(ACTION_STOP))
        }

        fun reconcile(context: Context) {
            if (GuardStore(context).liveTrackingActive) start(context) else stop(context)
        }
    }
}
