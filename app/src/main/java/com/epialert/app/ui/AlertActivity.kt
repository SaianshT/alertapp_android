package com.epialert.app.ui

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.epialert.app.R
import com.epialert.app.alarm.AlarmDispatcher
import com.epialert.app.databinding.ActivityAlertBinding
import com.epialert.app.model.SeizureAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AlertActivity — full-screen emergency takeover shown over the lock screen.
 *
 * Responsibilities:
 *  • Displays a high-contrast emergency UI with alert details
 *  • Shows over the lock screen, turns screen on, requests keyguard dismiss
 *  • "Acknowledge & Silence" stops the alarm service
 *  • "Call Emergency Contact" dials the configured number
 *  • Pulse animation on the ⚠️ icon for visual urgency
 */
class AlertActivity : AppCompatActivity() {

    companion object {
        private const val TAG                     = "AlertActivity"
        private const val TIMESTAMP_FORMAT        = "MMM dd, yyyy • HH:mm:ss"
        private const val PREF_EMERGENCY_CONTACT  = "emergency_contact_number"
        private const val DEFAULT_EMERGENCY_NUMBER = "911"
        private const val PULSE_INTERVAL_MS       = 800L
    }

    private lateinit var binding: ActivityAlertBinding
    private val handler = Handler(Looper.getMainLooper())
    private var alert: SeizureAlert? = null

    // Pulse animation runnable
    private val pulseRunnable = object : Runnable {
        private var expanded = false
        override fun run() {
            expanded = !expanded
            val scale = if (expanded) 1.15f else 1.0f
            binding.ivAlertIcon.animate()
                .scaleX(scale).scaleY(scale)
                .setDuration(PULSE_INTERVAL_MS / 2)
                .start()
            handler.postDelayed(this, PULSE_INTERVAL_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Show over lock screen & turn screen on ──────────────────────────
        configureWindowFlags()

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Parse alert from intent ──────────────────────────────────────────
        alert = extractAlert(intent)
        populateUi(alert)

        // ── Button listeners ─────────────────────────────────────────────────
        binding.btnAcknowledge.setOnClickListener { acknowledgeAlarm() }
        binding.btnCallEmergency.setOnClickListener { callEmergencyContact() }

        // ── Start pulse animation ─────────────────────────────────────────────
        handler.post(pulseRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Another alert arrived while this activity is still open
        alert = extractAlert(intent)
        populateUi(alert)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pulseRunnable)
    }

    // Prevent back button from dismissing the alarm
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally swallowed — alarm must be explicitly acknowledged
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window & lock-screen flags
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun configureWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        // Keep screen at full brightness
        window.attributes = window.attributes.also {
            it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }

        // Request keyguard dismiss (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { /* Keyguard dismissed */ }
                override fun onDismissCancelled() { /* User cancelled, alert still visible */ }
                override fun onDismissError()     { /* Error dismissing, alert still visible */ }
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI population
    // ─────────────────────────────────────────────────────────────────────────

    private fun populateUi(alert: SeizureAlert?) {
        if (alert == null) {
            // No alert data — show generic message
            binding.tvSeverityBadge.text = "EMERGENCY"
            binding.tvTimestamp.text     = formatTimestamp(System.currentTimeMillis())
            binding.tvLocation.text      = "Location: Unknown"
            binding.tvDeviceId.text      = "Device: Unknown"
            binding.tvSourceBadge.text   = "ALERT"
            return
        }

        // Severity badge
        binding.tvSeverityBadge.text = "${alert.severity} SEVERITY"
        val severityBg = when (alert.severity.uppercase()) {
            "HIGH"   -> R.color.severity_high
            "MEDIUM" -> R.color.severity_medium
            else     -> R.color.severity_low
        }
        binding.tvSeverityBadge.backgroundTintList =
            ContextCompat.getColorStateList(this, severityBg)

        // Details
        binding.tvTimestamp.text  = "Detected: ${formatTimestamp(alert.timestampMillis)}"
        binding.tvLocation.text   = "📍 Location: ${alert.location}"
        binding.tvDeviceId.text   = "🔌 Device: ${alert.deviceId}"
        binding.tvSender.text     = "📡 From: ${alert.senderAddress}"
        binding.tvSourceBadge.text = "via ${alert.source.name}"

        // High severity: flash the background red
        if (alert.severityLevel >= 3) {
            startBackgroundFlash()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background flash animation (HIGH severity)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startBackgroundFlash() {
        val flashRunnable = object : Runnable {
            private var flashOn = false
            override fun run() {
                flashOn = !flashOn
                binding.rootLayout.setBackgroundColor(
                    if (flashOn) 0xFF2D0A0A.toInt() else 0xFF1A0000.toInt()
                )
                handler.postDelayed(this, 600)
            }
        }
        handler.post(flashRunnable)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun acknowledgeAlarm() {
        // Visual feedback
        binding.btnAcknowledge.isEnabled = false
        binding.btnAcknowledge.text      = "✓ Silenced"

        // Stop the alarm service and cancel notification
        AlarmDispatcher.cancelAlarm(this)

        // Remove window flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Finish after brief delay
        handler.postDelayed({ finish() }, 1500)
    }

    private fun callEmergencyContact() {
        val prefs   = getSharedPreferences("EpiAlert_prefs", MODE_PRIVATE)
        val number  = prefs.getString(PREF_EMERGENCY_CONTACT, DEFAULT_EMERGENCY_NUMBER)
            ?: DEFAULT_EMERGENCY_NUMBER

        val dialIntent = Intent(Intent.ACTION_CALL).apply {
            data  = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            startActivity(dialIntent)
        } catch (e: SecurityException) {
            // CALL_PHONE permission not granted — fall back to dial UI
            val dialUiIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
            }
            startActivity(dialUiIntent)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractAlert(intent: Intent): SeizureAlert? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(SeizureAlert.EXTRA_KEY, SeizureAlert::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(SeizureAlert.EXTRA_KEY)
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.getDefault())
        return sdf.format(Date(epochMs))
    }
}
