package com.epialert.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.messaging.FirebaseMessaging
import com.epialert.app.alarm.AlarmDispatcher
import com.epialert.app.databinding.ActivityMainBinding
import com.epialert.app.model.SeizureAlert

/**
 * MainActivity — Permission onboarding hub and app status dashboard.
 *
 * Checks and requests:
 *  1. POST_NOTIFICATIONS (Android 13+)
 *  2. RECEIVE_SMS / READ_SMS
 *  3. CALL_PHONE
 *  4. USE_FULL_SCREEN_INTENT (Android 14+ — redirects to Settings)
 *  5. Battery optimization exemption
 *  6. Emergency contact number configuration
 *
 * Also displays the current FCM token for backend registration and
 * provides a "Test Alarm" trigger for development verification.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG                    = "MainActivity"
        private const val PREF_FILE              = "EpiAlert_prefs"
        private const val PREF_EMERGENCY_CONTACT = "emergency_contact_number"
    }

    private lateinit var binding: ActivityMainBinding

    // ─────────────────────────────────────────────────────────────────────────
    // Permission launchers
    // ─────────────────────────────────────────────────────────────────────────

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.all { it.value }
        updatePermissionRow(
            binding.rowSms,
            granted,
            "SMS permission granted — hardware alerts will be received."
        )
        refreshStatusDots()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionRow(
            binding.rowNotification,
            granted,
            if (granted) "Notification permission granted."
            else "Notification permission denied — alerts cannot show."
        )
        refreshStatusDots()
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionRow(
            binding.rowCall,
            granted,
            if (granted) "Call permission granted."
            else "Call permission denied — emergency dial will open dialler instead."
        )
        refreshStatusDots()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-evaluate all permissions when returning from Settings
        refreshStatusDots()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        AlarmDispatcher.ensureChannel(this)

        setupPermissionRows()
        setupActionButtons()
        loadFcmToken()
        loadEmergencyContact()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions any time we come back (e.g. from Settings)
        refreshStatusDots()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission rows setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupPermissionRows() {
        // SMS row
        binding.rowSms.setOnClickListener { requestSmsPermissions() }

        // Notifications row
        binding.rowNotification.setOnClickListener { requestNotificationPermission() }

        // CALL_PHONE row
        binding.rowCall.setOnClickListener { requestCallPermission() }

        // Full-screen intent row (Android 14+)
        binding.rowFullScreenIntent.setOnClickListener { checkFullScreenIntentPermission() }

        // Battery optimisation row
        binding.rowBatteryOptimisation.setOnClickListener { requestBatteryOptimisationExemption() }

        refreshStatusDots()
    }

    @SuppressLint("BatteryLife")
    private fun setupActionButtons() {
        // Save emergency contact
        binding.btnSaveContact.setOnClickListener {
            val number = binding.etEmergencyContact.text.toString().trim()
            if (number.isNotEmpty()) {
                getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                    .edit().putString(PREF_EMERGENCY_CONTACT, number).apply()
                Toast.makeText(this, "Emergency contact saved: $number", Toast.LENGTH_SHORT).show()
            } else {
                binding.etEmergencyContact.error = "Enter a valid phone number"
            }
        }

        // Test alarm button
        binding.btnTestAlarm.setOnClickListener {
            val testAlert = SeizureAlert.createTestAlert()
            AlarmDispatcher.triggerAlarm(this, testAlert)
            Toast.makeText(this, "🚨 Test alarm triggered!", Toast.LENGTH_SHORT).show()
        }

        // Copy FCM token
        binding.btnCopyToken.setOnClickListener {
            val token = binding.tvFcmToken.text.toString()
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FCM Token", token))
            Toast.makeText(this, "FCM token copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission requests
    // ─────────────────────────────────────────────────────────────────────────

    private fun requestSmsPermissions() {
        if (hasSmsPermissions()) {
            showSnack("SMS permissions already granted ✓")
            return
        }
        smsPermissionLauncher.launch(
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showSnack("Notification permissions are auto-granted on this Android version ✓")
            return
        }
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            showSnack("Notification permission already granted ✓")
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestCallPermission() {
        if (hasPermission(Manifest.permission.CALL_PHONE)) {
            showSnack("Call permission already granted ✓")
            return
        }
        callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            showSnack("Full-screen intent is auto-granted on Android 13 and below ✓")
            return
        }

        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) {
            showSnack("Full-screen intent permission already granted ✓")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Full-Screen Intent Required")
            .setMessage(
                "Android 14 requires you to manually grant EpiAlert permission to show " +
                        "emergency alerts over the lock screen.\n\n" +
                        "Tap 'Open Settings', find EpiAlert, and enable 'Allow full-screen intents'."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                settingsLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimisationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            showSnack("Battery optimization already disabled ✓")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Disable Battery Optimization")
            .setMessage(
                "To ensure seizure alerts are delivered even when the phone is idle or in Doze mode, " +
                        "EpiAlert needs to be excluded from battery optimization.\n\n" +
                        "This does NOT significantly impact battery life."
            )
            .setPositiveButton("Disable Optimization") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                settingsLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status indicator refresh
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshStatusDots() {
        // SMS
        setRowStatus(binding.rowSms, hasSmsPermissions())

        // Notifications
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        setRowStatus(binding.rowNotification, notifGranted)

        // CALL_PHONE
        setRowStatus(binding.rowCall, hasPermission(Manifest.permission.CALL_PHONE))

        // Full-screen intent
        val fsiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.canUseFullScreenIntent()
        } else true
        setRowStatus(binding.rowFullScreenIntent, fsiGranted)

        // Battery optimisation
        val pm = getSystemService(PowerManager::class.java)
        setRowStatus(binding.rowBatteryOptimisation, pm.isIgnoringBatteryOptimizations(packageName))

        // Overall readiness indicator
        val allGranted = hasSmsPermissions() && notifGranted && fsiGranted
        binding.tvReadinessStatus.text = if (allGranted) {
            "✅ EpiAlert is active and monitoring"
        } else {
            "⚠️ Action required — tap red items above"
        }
        binding.tvReadinessStatus.setTextColor(
            ContextCompat.getColor(
                this, if (allGranted) R.color.status_ok else R.color.status_warn
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FCM Token
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                binding.tvFcmToken.text = token
            } else {
                binding.tvFcmToken.text = "Could not retrieve token — check google-services.json"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emergency contact
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadEmergencyContact() {
        val prefs  = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
        val number = prefs.getString(PREF_EMERGENCY_CONTACT, "")
        if (!number.isNullOrEmpty()) {
            binding.etEmergencyContact.setText(number)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setRowStatus(row: android.view.View, granted: Boolean) {
        val dot = row.findViewById<android.widget.ImageView>(R.id.ivStatusDot)
        dot?.setImageResource(
            if (granted) R.drawable.ic_status_ok else R.drawable.ic_status_warn
        )
        dot?.setColorFilter(
            ContextCompat.getColor(this, if (granted) R.color.status_ok else R.color.status_warn)
        )
    }

    private fun updatePermissionRow(row: android.view.View, granted: Boolean, message: String) {
        setRowStatus(row, granted)
        showSnack(message)
    }

    private fun showSnack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission checks
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasSmsPermissions(): Boolean =
        hasPermission(Manifest.permission.RECEIVE_SMS) &&
                hasPermission(Manifest.permission.READ_SMS)
}
