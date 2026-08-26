package com.epialert.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.epialert.app.alarm.AlarmDispatcher

/**
 * EpiAlertApp — Application class.
 * Initialises Firebase and ensures the emergency notification channel
 * is created at startup so notifications work immediately.
 */
class EpiAlertApp : Application() {

    companion object {
        private const val TAG = "EpiAlertApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "EpiAlert application starting")

        // Initialise Firebase (reads google-services.json)
        FirebaseApp.initializeApp(this)

        // Pre-create the emergency notification channel so it is ready
        // before any alert arrives (channel creation is idempotent)
        AlarmDispatcher.ensureChannel(this)

        Log.i(TAG, "Initialisation complete")
    }
}
