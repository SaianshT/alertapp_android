package com.epialert.app.service

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.epialert.app.R
import com.epialert.app.alarm.AlarmDispatcher
import com.epialert.app.model.SeizureAlert

/**
 * AlarmForegroundService — keeps the alarm sound and vibration alive
 * even if the user navigates away from AlertActivity or the system
 * tries to reclaim resources.
 *
 * Lifecycle:
 *  START_ALARM → plays looping MediaPlayer with USAGE_ALARM audio attrs
 *  STOP_ALARM  → releases player, stops self
 */
class AlarmForegroundService : Service() {

    companion object {
        private const val TAG             = "AlarmFgService"
        const val ACTION_START_ALARM      = "com.EpiAlert.ACTION_START_ALARM"
        const val ACTION_STOP_ALARM       = "com.EpiAlert.ACTION_STOP_ALARM"
        private const val NOTIFICATION_ID = 9002

        // Vibration pattern: wait 0 ms, vibrate 600, pause 400, repeat
        private val VIBRATION_PATTERN     = longArrayOf(0, 600, 400)
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator?       = null

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        vibrator = getVibrator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val alert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(SeizureAlert.EXTRA_KEY, SeizureAlert::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(SeizureAlert.EXTRA_KEY)
                }

                Log.i(TAG, "Starting alarm for: ${alert?.deviceId}")
                startForegroundWithNotification(alert)
                startAlarmSound()
                startVibration()
            }

            ACTION_STOP_ALARM -> {
                Log.i(TAG, "Stopping alarm")
                stopAlarm()
                stopSelf()
            }

            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action} — stopping self")
                stopSelf()
            }
        }

        // START_STICKY: system will restart service with null intent if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        Log.d(TAG, "Service destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground notification (keeps the service alive)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startForegroundWithNotification(alert: SeizureAlert?) {
        AlarmDispatcher.ensureChannel(this)

        val notification = NotificationCompat.Builder(this, AlarmDispatcher.CHANNEL_ID_EMERGENCY)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle("⚠️ SEIZURE DETECTED — Alarm Active")
            .setContentText(
                "Severity: ${alert?.severity ?: "HIGH"} • ${alert?.location ?: "Unknown"}"
            )
            .setColor(ContextCompat.getColor(this, R.color.alert_red))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio engine
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAlarmSound() {
        try {
            stopAlarmSound()  // Release any existing player

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                // Override stream volume to ALARM so it bypasses silent/DND
                setAudioStreamType(AudioManager.STREAM_ALARM)
                setDataSource(
                    applicationContext,
                    android.net.Uri.parse(
                        "android.resource://${packageName}/${R.raw.alarm_sound}"
                    )
                )
                isLooping = true
                setVolume(1.0f, 1.0f)   // Full volume
                prepare()
                start()
            }

            Log.d(TAG, "Alarm sound started (looping)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vibration
    // ─────────────────────────────────────────────────────────────────────────

    private fun startVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0) // repeat index 0
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VIBRATION_PATTERN, 0)
            }
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }

    private fun stopAlarm() {
        stopAlarmSound()
        stopVibration()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }
}
