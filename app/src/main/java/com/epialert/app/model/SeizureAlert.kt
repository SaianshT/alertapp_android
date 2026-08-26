package com.epialert.app.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * SeizureAlert — immutable data model carried across components via Intent extras.
 *
 * Parcelable so it can be passed directly in Intent bundles between
 * BroadcastReceiver → AlarmDispatcher → AlarmForegroundService → AlertActivity.
 */
@Parcelize
data class SeizureAlert(
    /** Phone number or hardware device identifier that sent the alert */
    val senderAddress: String,

    /** Epoch milliseconds of detection event */
    val timestampMillis: Long,

    /** Human-readable location tag from the hardware payload */
    val location: String,

    /** Severity level string: "HIGH" | "MEDIUM" | "LOW" */
    val severity: String,

    /** Hardware device identifier */
    val deviceId: String,

    /** How the alert was received */
    val source: Source
) : Parcelable {

    enum class Source { SMS, FCM, TEST }

    /** Severity as ordinal for visual colour coding */
    val severityLevel: Int get() = when (severity.uppercase()) {
        "HIGH"   -> 3
        "MEDIUM" -> 2
        "LOW"    -> 1
        else     -> 3
    }

    /** Badge colour resource name (resolved by AlertActivity) */
    val severityColorName: String get() = when (severity.uppercase()) {
        "HIGH"   -> "severity_high"
        "MEDIUM" -> "severity_medium"
        "LOW"    -> "severity_low"
        else     -> "severity_high"
    }

    companion object {
        const val EXTRA_KEY = "extra_seizure_alert"

        /** Factory for in-app test triggers */
        fun createTestAlert() = SeizureAlert(
            senderAddress   = "TEST-DEVICE",
            timestampMillis = System.currentTimeMillis(),
            location        = "Test Location",
            severity        = "HIGH",
            deviceId        = "SGD-TEST",
            source          = Source.TEST
        )
    }
}
