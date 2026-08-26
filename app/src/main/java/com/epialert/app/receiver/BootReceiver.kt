package com.epialert.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.epialert.app.alarm.AlarmDispatcher

/**
 * BootReceiver — re-creates the notification channel and ensures
 * all background monitors are active after a device reboot.
 *
 * The SMS BroadcastReceiver registered in the manifest is re-armed
 * automatically by the OS; this receiver handles any additional
 * initialisation that the Application class would normally do on
 * a cold start (e.g., restoring notification channels after
 * DIRECT_BOOT wipes volatile state).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED") return

        Log.i(TAG, "Device boot completed — reinitialising EpiAlert")

        // Re-create notification channel (survives reboots on Android 8+,
        // but harmless to recreate — channel creation is idempotent)
        AlarmDispatcher.ensureChannel(context)

        Log.i(TAG, "Boot re-initialisation complete")
    }
}
