package com.epialert.app.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.epialert.app.alarm.AlarmDispatcher
import com.epialert.app.model.SeizureAlert

/**
 * AppFirebaseMessagingService — handles high-priority FCM data payloads.
 *
 * Expected FCM data payload keys:
 * ┌──────────────┬──────────────────────────────────┐
 * │ Key          │ Value example                    │
 * ├──────────────┼──────────────────────────────────┤
 * │ type         │ "SEIZURE_DETECTED"               │
 * │ timestamp    │ "1724684550" (epoch seconds)     │
 * │ location     │ "Living Room"                    │
 * │ severity     │ "HIGH" | "MEDIUM" | "LOW"        │
 * │ device_id    │ "SGD-001"                        │
 * │ sender       │ "+15551234567"                   │
 * └──────────────┴──────────────────────────────────┘
 *
 * The message MUST be sent as a DATA-only message (not notification message)
 * with android.priority=high in the FCM payload so Android delivers it
 * immediately even in Doze/standby. Example FCM JSON to send:
 *
 * {
 *   "message": {
 *     "token": "<device_fcm_token>",
 *     "android": { "priority": "high" },
 *     "data": {
 *       "type": "SEIZURE_DETECTED",
 *       "timestamp": "1724684550",
 *       "location": "Living Room",
 *       "severity": "HIGH",
 *       "device_id": "SGD-001",
 *       "sender": "SGD-Hardware"
 *     }
 *   }
 * }
 */
class AppFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"

        // Data payload keys
        const val KEY_TYPE      = "type"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_LOCATION  = "location"
        const val KEY_SEVERITY  = "severity"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SENDER    = "sender"

        // Expected type value that triggers the alarm
        const val TYPE_SEIZURE_DETECTED = "SEIZURE_DETECTED"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when a new FCM registration token is generated.
     * In production: upload this token to your backend so the hardware
     * device's cloud service can target this specific phone.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed — upload to server: ${token.take(20)}…")
        // TODO: Upload token to your backend / Firestore
        // TokenRepository.uploadToken(token)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message received
    // ─────────────────────────────────────────────────────────────────────────

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")
        Log.d(TAG, "FCM data payload: ${remoteMessage.data}")

        // If the server sends a notification-type message, log and ignore.
        // We require DATA-only for background wake capability.
        remoteMessage.notification?.let { notif ->
            Log.w(TAG, "Notification payload received (not data-only). " +
                    "Switch to data-only FCM for reliable background delivery. " +
                    "Title=${notif.title}")
        }

        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.w(TAG, "Empty FCM data payload — ignoring")
            return
        }

        val type = data[KEY_TYPE]
        if (type != TYPE_SEIZURE_DETECTED) {
            Log.d(TAG, "FCM type='$type' is not a seizure alert — ignoring")
            return
        }

        Log.i(TAG, "⚠️ SEIZURE ALERT received via FCM!")
        val alert = buildAlertFromPayload(data, remoteMessage)
        AlarmDispatcher.triggerAlarm(applicationContext, alert)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildAlertFromPayload(
        data: Map<String, String>,
        remoteMessage: RemoteMessage
    ): SeizureAlert {
        val rawTimestamp = data[KEY_TIMESTAMP]?.toLongOrNull()
            ?: (remoteMessage.sentTime.takeIf { it > 0L } ?: System.currentTimeMillis() / 1000L)

        // Normalise to milliseconds
        val timestampMillis = if (rawTimestamp < 1_000_000_000_000L) rawTimestamp * 1000L else rawTimestamp

        return SeizureAlert(
            senderAddress   = data[KEY_SENDER]    ?: remoteMessage.from ?: "FCM",
            timestampMillis = timestampMillis,
            location        = data[KEY_LOCATION]  ?: "Unknown location",
            severity        = data[KEY_SEVERITY]  ?: "HIGH",
            deviceId        = data[KEY_DEVICE_ID] ?: "Unknown device",
            source          = SeizureAlert.Source.FCM
        )
    }
}
