package com.morningsearch.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class RecoverAlarmController(private val context: Context) {
    private val store = GuardStore(context)

    fun start(): Boolean {
        if (player?.isPlaying == true) {
            store.setRecoverAlarmActive(true)
            return true
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        stop()
        player = MediaPlayer().apply {
            setDataSource(context, uri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            prepare()
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        player?.start()
        vibrate()
        store.setRecoverAlarmActive(true)
        return true
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        store.setRecoverAlarmActive(false)
    }

    private fun vibrate() {
        val pattern = longArrayOf(0L, 600L, 300L, 600L)
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    companion object {
        private var player: MediaPlayer? = null
    }
}
