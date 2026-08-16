package com.morningsearch.guard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

class RecoveryCaptureActivity : Activity() {
    private lateinit var status: TextView
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private val timeline by lazy { RecoverTimeline(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FRONT
        if (mode == MODE_AUDIO) {
            buildAudioScreen()
            ensureAudioPermission()
        } else {
            buildCameraScreen(mode)
            ensureCameraPermission()
        }
    }

    override fun onDestroy() {
        stopCamera()
        stopAudio()
        super.onDestroy()
    }

    private fun buildCameraScreen(mode: String) {
        val texture = TextureView(this)
        status = TextView(this).apply {
            text = if (mode == MODE_REAR) "Recovery rear camera active" else "Recovery front camera active"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera(mode, texture)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun buildAudioScreen() {
        status = TextView(this).apply {
            text = "Recovery audio recording will start"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(38, 40, 45))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun ensureAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } else {
            startAudioRecording()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            timeline.record("capture_permission_denied", permissions.firstOrNull().orEmpty())
            finish()
            return
        }
        if (requestCode == REQUEST_AUDIO) startAudioRecording()
        if (requestCode == REQUEST_CAMERA) recreate()
    }

    private fun openCamera(mode: String, texture: TextureView) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraThread = HandlerThread("recover_camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = selectCamera(manager, if (mode == MODE_REAR) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT)
        if (cameraId == null) {
            timeline.record("capture_failed", "Requested camera not available")
            finish()
            return
        }
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreviewAndCapture(camera, texture)
            }
            override fun onDisconnected(camera: CameraDevice) {
                timeline.record("capture_failed", "Camera disconnected")
                finish()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                timeline.record("capture_failed", "Camera error $error")
                finish()
            }
        }, cameraHandler)
    }

    private fun startPreviewAndCapture(camera: CameraDevice, texture: TextureView) {
        val surfaceTexture = texture.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1280, 720)
        val previewSurface = Surface(surfaceTexture)
        imageReader = ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.JPEG, 1)
        val reader = imageReader ?: return
        reader.setOnImageAvailableListener({ available ->
            val file = File(filesDir, "recover/photo_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
            available.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(file).use { it.write(bytes) }
            }
            timeline.record("photo_captured", file.absolutePath)
            RecoverUploadManager(this).uploadFileAsync(file, "photo")
            status.text = "Recovery photo saved"
            Handler(mainLooper).postDelayed({
                startNextCaptureOrFinish()
            }, 1200L)
        }, cameraHandler)
        camera.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                }.build()
                configured.setRepeatingRequest(preview, null, cameraHandler)
                Handler(mainLooper).postDelayed({ captureStill(camera, configured, reader.surface) }, 2000L)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                timeline.record("capture_failed", "Camera session configuration failed")
                finish()
            }
        }, cameraHandler)
    }

    private fun captureStill(camera: CameraDevice, configured: CameraCaptureSession, surface: Surface) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(surface)
        }.build()
        configured.capture(request, null, cameraHandler)
        status.text = "Capturing recovery photo"
    }

    private fun selectCamera(manager: CameraManager, facing: Int): String? {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun startAudioRecording() {
        val file = File(filesDir, "recover/audio_${System.currentTimeMillis()}.m4a").also { it.parentFile?.mkdirs() }
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 20).coerceIn(5, 120)
        status.text = "Recovery audio recording: ${seconds}s"
        Handler(mainLooper).postDelayed({
            stopAudio()
            timeline.record("audio_recorded", file.absolutePath)
            RecoverUploadManager(this).uploadFileAsync(file, "audio")
            status.text = "Recovery audio saved"
            Handler(mainLooper).postDelayed({ startNextCaptureOrFinish() }, 1000L)
        }, seconds * 1000L)
    }

    private fun startNextCaptureOrFinish() {
        val next = intent.getStringExtra(EXTRA_NEXT_MODE)
        if (!next.isNullOrBlank()) {
            startActivity(
                android.content.Intent(this, RecoveryCaptureActivity::class.java)
                    .putExtra(EXTRA_MODE, next)
                    .putExtra(EXTRA_SECONDS, intent.getIntExtra(EXTRA_SECONDS, 20))
            )
        }
        finish()
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        cameraThread?.quitSafely()
        session = null
        cameraDevice = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
    }

    private fun stopAudio() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_NEXT_MODE = "next_mode"
        const val EXTRA_SECONDS = "seconds"
        const val MODE_FRONT = "front"
        const val MODE_REAR = "rear"
        const val MODE_AUDIO = "audio"
        private const val REQUEST_CAMERA = 4010
        private const val REQUEST_AUDIO = 4011
    }
}
