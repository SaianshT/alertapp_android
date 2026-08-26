package com.epialert.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.epialert.app.R
import com.epialert.app.model.SeizureAlert
import com.epialert.app.service.AlarmForegroundService
import com.epialert.app.ui.AlertActivity

/**
 * AlarmDispatcher — central coordinator that wakes the device, posts the
 * full-screen notification, and starts the foreground alarm service.
 *
 * Called by both [SmsReceiver] and [AppFirebaseMessagingService].
 */
object AlarmDispatcher {

    private const val TAG                    = "AlarmDispatcher"
    const val CHANNEL_ID_EMERGENCY           = "seizure_emergency_channel"
    const val CHANNEL_NAME_EMERGENCY         = "Seizure Emergency Alerts"
    const val NOTIFICATION_ID_ALERT          = 9001

    // Wake lock tag
    private const val WAKE_LOCK_TAG          = "EpiAlert:AlarmWakeLock"
    private const val WAKE_LOCK_TIMEOUT_MS   = 60_000L   // 60 s max hold

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wakes the device and fires the full-screen emergency alarm.
     * Safe to call from any background thread.
     */
    fun triggerAlarm(context: Context, alert: SeizureAlert) {
        Log.i(TAG, "Triggering alarm for alert: $alert")

        // 1. Acquire CPU wake lock so code keeps running after screen off
        acquireTemporaryWakeLock(context)

        // 2. Ensure the notification channel exists
        ensureChannel(context)

        // 3. Start the foreground service (keeps alarm sound alive if app is backgrounded)
        startAlarmService(context, alert)

        // 4. Post the full-screen notification
        postFullScreenNotification(context, alert)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wake lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireTemporaryWakeLock(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            )
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock acquired for ${WAKE_LOCK_TIMEOUT_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel setup
    // ─────────────────────────────────────────────────────────────────────────

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID_EMERGENCY) != null) return

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID_EMERGENCY,
            CHANNEL_NAME_EMERGENCY,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description             = "Critical seizure detection alerts from EpiAlert hardware"
            enableLights(true)
            lightColor              = 0xFFFF0000.toInt()    // Red LED
            enableVibration(true)
            vibrationPattern        = longArrayOf(0, 400, 200, 400, 200, 800)
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_sound}"),
                audioAttrs
            )
            setBypassDnd(true)          // Override Do Not Disturb
            lockscreenVisibility    = NotificationCompat.VISIBILITY_PUBLIC
            importance              = NotificationManager.IMPORTANCE_HIGH
        }

        nm.createNotificationChannel(channel)
        Log.d(TAG, "Emergency notification channel created")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full-screen notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun postFullScreenNotification(context: Context, alert: SeizureAlert) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Check Android 14+ full-screen intent permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!nm.canUseFullScreenIntent()) {
                Log.w(TAG, "USE_FULL_SCREEN_INTENT not granted — notification will show in shade only")
                // The foreground service will still play the alarm sound
            }
        }

        // PendingIntent that opens AlertActivity as full-screen
        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(SeizureAlert.EXTRA_KEY, alert)
        }

        val fullScreenPi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_ALERT,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_EMERGENCY)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle("⚠️ SEIZURE DETECTED")
            .setContentText("${alert.severity} severity • ${alert.location} • Device: ${alert.deviceId}")
            .setSubText("EpiAlert Emergency Alert")
            .setColor(ContextCompat.getColor(context, R.color.alert_red))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)                           // Cannot be swiped away
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPi, true)    // Launch AlertActivity over lock screen
            .setContentIntent(fullScreenPi)
            .setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_sound}"),
                android.media.AudioManager.STREAM_ALARM
            )
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 800))
            .setLights(0xFFFF0000.toInt(), 500, 500)
            .setUsesChronometer(true)
            .setWhen(alert.timestampMillis)
            .build()

        nm.notify(NOTIFICATION_ID_ALERT, notification)
        Log.i(TAG, "Full-screen emergency notification posted")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground service
    // ─────────────────────────────────────────────────────────────────────────

    private fun startAlarmService(context: Context, alert: SeizureAlert) {
        val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
            putExtra(SeizureAlert.EXTRA_KEY, alert)
            action = AlarmForegroundService.ACTION_START_ALARM
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        Log.d(TAG, "AlarmForegroundService start requested")
    }

    /**
     * Cancel the ongoing alarm notification (called from AlertActivity on acknowledge).
     */
    fun cancelAlarm(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_ALERT)

        val stopIntent = Intent(context, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_STOP_ALARM
        }
        context.startService(stopIntent)
        Log.i(TAG, "Alarm cancelled")
    }
}
