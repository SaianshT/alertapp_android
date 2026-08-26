package com.epialert.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import com.epialert.app.alarm.AlarmDispatcher
import com.epialert.app.model.SeizureAlert

/**
 * SmsReceiver — intercepts incoming SMS messages and fires the seizure alarm
 * when a message contains the expected hardware alert prefix.
 *
 * Expected SMS format:
 *   ALERT:SEIZURE_DETECTED|timestamp=<epoch>|location=<loc>|severity=<HIGH|MEDIUM|LOW>|device=<id>
 *
 * Example:
 *   ALERT:SEIZURE_DETECTED|timestamp=1724684550|location=Living Room|severity=HIGH|device=SGD-001
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val SMS_ALERT_PREFIX    = "ALERT:SEIZURE_DETECTED"
        const val PAYLOAD_SEPARATOR   = "|"
        const val KEY_TIMESTAMP       = "timestamp"
        const val KEY_LOCATION        = "location"
        const val KEY_SEVERITY        = "severity"
        const val KEY_DEVICE_ID       = "device"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        Log.d(TAG, "SMS_RECEIVED broadcast caught")

        val messages = extractSmsMessages(intent) ?: return

        for (sms in messages) {
            val body   = sms.messageBody ?: continue
            val sender = sms.originatingAddress ?: "Unknown"

            Log.d(TAG, "SMS from $sender: ${body.take(80)}…")

            if (body.startsWith(SMS_ALERT_PREFIX)) {
                Log.i(TAG, "⚠️ SEIZURE ALERT SMS detected from $sender")
                val alert = parseAlertPayload(body, sender)
                AlarmDispatcher.triggerAlarm(context, alert)
                return  // One alert per broadcast is sufficient
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts [SmsMessage] objects from the raw SMS PDUs bundled in the Intent.
     * Handles both legacy and Android 6+ formats.
     */
    @Suppress("DEPRECATION")
    private fun extractSmsMessages(intent: Intent): List<SmsMessage>? {
        val bundle = intent.extras ?: run {
            Log.w(TAG, "SMS intent has no extras bundle")
            return null
        }

        val pdus = bundle.get("pdus") as? Array<*> ?: run {
            Log.w(TAG, "No PDUs in SMS bundle")
            return null
        }

        val format = bundle.getString("format")

        return pdus.mapNotNull { pdu ->
            val bytes = pdu as? ByteArray ?: return@mapNotNull null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(bytes, format)
            } else {
                SmsMessage.createFromPdu(bytes)
            }
        }
    }

    /**
     * Parses the key=value pairs after the alert prefix into a [SeizureAlert].
     *
     * Input example:
     *   ALERT:SEIZURE_DETECTED|timestamp=1724684550|location=Living Room|severity=HIGH|device=SGD-001
     */
    private fun parseAlertPayload(body: String, sender: String): SeizureAlert {
        val params = mutableMapOf<String, String>()

        // Strip the prefix, then split remaining tokens on '|'
        val payload = body.removePrefix(SMS_ALERT_PREFIX)
        payload.split(PAYLOAD_SEPARATOR).forEach { token ->
            val parts = token.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0].trim()] = parts[1].trim()
            }
        }

        val rawTimestamp = params[KEY_TIMESTAMP]?.toLongOrNull()
            ?: System.currentTimeMillis() / 1000L
        val timestampMillis = if (rawTimestamp < 1_000_000_000_000L) rawTimestamp * 1000L else rawTimestamp

        return SeizureAlert(
            senderAddress   = sender,
            timestampMillis = timestampMillis,
            location        = params[KEY_LOCATION]  ?: "Unknown location",
            severity        = params[KEY_SEVERITY]  ?: "HIGH",
            deviceId        = params[KEY_DEVICE_ID] ?: "Unknown device",
            source          = SeizureAlert.Source.SMS
        )
    }
}
