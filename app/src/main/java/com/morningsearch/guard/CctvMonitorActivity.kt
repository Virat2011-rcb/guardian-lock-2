package com.morningsearch.guard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class CctvMonitorActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var texture: TextureView
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var lastBrightness: Double? = null
    private var lastMotionAt = 0L
    private var detectionRunning = false
    private val store by lazy { GuardStore(this) }
    private val timeline by lazy { RecoverTimeline(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        store.setCctvMonitorActive(true)
        buildScreen()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            if (texture.isAvailable) openCamera()
        }
    }

    override fun onDestroy() {
        detectionRunning = false
        stopCamera()
        store.setCctvMonitorActive(false)
        timeline.record("cctv_monitor_stopped", "Visible CCTV Monitor Mode stopped")
        RecoverUploadManager(this).uploadEventAsync("cctv_monitor_stopped", "Visible CCTV Monitor Mode stopped")
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable) openCamera()
        } else {
            timeline.record("cctv_permission_denied", "Camera permission denied")
            finish()
        }
    }

    private fun buildScreen() {
        texture = TextureView(this)
        status = TextView(this).apply {
            text = "CCTV Monitor Mode active\nMotion alerts upload to your recovery server."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(18, 18, 18, 18)
        }
        val stop = Button(this).apply {
            text = "Stop CCTV Monitor"
            setOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun openCamera() {
        if (cameraDevice != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        if (cameraId == null) {
            timeline.record("cctv_failed", "No camera available")
            finish()
            return
        }
        cameraThread = HandlerThread("guardian_cctv_camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                timeline.record("cctv_failed", "Camera disconnected")
                finish()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                timeline.record("cctv_failed", "Camera error $error")
                finish()
            }
        }, cameraHandler)
    }

    private fun startPreview(camera: CameraDevice) {
        val surfaceTexture = texture.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1280, 720)
        val surface = Surface(surfaceTexture)
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }.build()
                configured.setRepeatingRequest(request, null, cameraHandler)
                timeline.record("cctv_monitor_started", "Visible CCTV Monitor Mode started")
                RecoverUploadManager(this@CctvMonitorActivity).uploadEventAsync("cctv_monitor_started", "Visible CCTV Monitor Mode started")
                detectionRunning = true
                Handler(mainLooper).postDelayed({ detectMotionLoop() }, 900L)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                timeline.record("cctv_failed", "Camera session configuration failed")
                finish()
            }
        }, cameraHandler)
    }

    private fun detectMotionLoop() {
        if (!detectionRunning || isFinishing || isDestroyed) return
        if (!store.cctvMonitorActive) {
            finish()
            return
        }
        val current = frameBrightness(texture.bitmap ?: run {
            Handler(mainLooper).postDelayed({ detectMotionLoop() }, DETECTION_INTERVAL_MS)
            return
        })
        val previous = lastBrightness
        lastBrightness = current
        val delta = previous?.let { abs(current - it) } ?: 0.0
        val now = System.currentTimeMillis()
        if (delta >= MOTION_THRESHOLD && now - lastMotionAt >= MOTION_COOLDOWN_MS) {
            lastMotionAt = now
            val detail = "Motion detected by visible CCTV Monitor Mode. Score %.1f".format(delta)
            status.text = "CCTV Monitor Mode active\nMotion detected: %.1f".format(delta)
            timeline.record("motion_detected", detail)
            RecoverUploadManager(this).uploadEventAsync("motion_detected", detail)
        }
        Handler(mainLooper).postDelayed({ detectMotionLoop() }, DETECTION_INTERVAL_MS)
    }

    private fun frameBrightness(bitmap: Bitmap): Double {
        val scaled = Bitmap.createScaledBitmap(bitmap, 24, 18, false)
        var total = 0L
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val pixel = scaled.getPixel(x, y)
                total += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        return total.toDouble() / (24 * 18)
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        cameraThread?.quitSafely()
        session = null
        cameraDevice = null
        cameraThread = null
        cameraHandler = null
    }

    companion object {
        private const val REQUEST_CAMERA = 4020
        private const val DETECTION_INTERVAL_MS = 700L
        private const val MOTION_COOLDOWN_MS = 1_000L
        private const val MOTION_THRESHOLD = 10.0
    }
}
