package com.morningsearch.guard

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

class RecoverFlashlightController(private val context: Context) {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val store = GuardStore(context)

    fun start(): Boolean {
        val id = torchCameraId() ?: return false
        if (running) {
            store.setRecoverFlashlightActive(true)
            return true
        }
        currentCamera = camera
        currentId = id
        store.setRecoverFlashlightActive(true)
        running = true
        torchOn = false
        handler.post(blink)
        return true
    }

    fun stop() {
        handler.removeCallbacks(blink)
        currentId?.let { id -> runCatching { currentCamera?.setTorchMode(id, false) } }
        running = false
        torchOn = false
        currentCamera = null
        currentId = null
        store.setRecoverFlashlightActive(false)
    }

    private fun torchCameraId(): String? = runCatching {
        camera.cameraIdList.firstOrNull { id ->
            val c = camera.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private var currentCamera: CameraManager? = null
        private var currentId: String? = null
        private var running = false
        private var torchOn = false
        private val blink = object : Runnable {
            override fun run() {
                val camera = currentCamera
                val id = currentId
                if (!running || camera == null || id == null) return
                torchOn = !torchOn
                runCatching { camera.setTorchMode(id, torchOn) }
                handler.postDelayed(this, 450L)
            }
        }
    }
}
