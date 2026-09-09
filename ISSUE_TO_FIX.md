# 🚨 ISSUE TO FIX: AlertActivity Does Not Launch from Background / Lock Screen

## 1. Problem Description & Current State

### ✅ What Currently Works
- **App Open & Focused**: Pressing "Trigger Test Alarm" immediately pushes `AlertActivity` to the screen on top of `MainActivity`. The full-screen red emergency UI displays, audio plays, vibration triggers, and acknowledge/call buttons work as expected.

### ❌ What Is Currently Broken
1. **App Closed / Killed**:
   - When an SMS arrives (`ALERT:SEIZURE_DETECTED`), the device receives the SMS and `SmsReceiver` triggers.
   - The alarm audio and vibration play via `AlarmForegroundService`.
   - **However, `AlertActivity` does NOT appear on screen.** Only the sound plays in the background, and a standard notification appears in the notification drawer.
2. **App Backgrounded / Not Focused**:
   - Audio and vibration play, but the device stays on the current app/home screen. The user must manually swipe down the notification shade or switch apps via the task switcher to see the red emergency screen.
3. **Phone Locked / Screen Off**:
   - Audio and vibration play, the screen may turn on briefly or stay dimmed, but the lock screen is shown instead of the emergency alert UI.

---

## 2. Root Cause Analysis

### Cause 1: Background Activity Launch (BAL) Restrictions (Android 10 - Android 14)
- Since Android 10 (API 29), and reinforced aggressively in Android 12, 13, and 14, Android **strictly forbids apps from calling `context.startActivity()` from the background** (e.g. from `BroadcastReceiver` or background `Service`), even with `FLAG_ACTIVITY_NEW_TASK`.
- In `AlarmDispatcher.kt`, `launchAlertActivity()` attempts:
  ```kotlin
  val intent = Intent(context, AlertActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ...
  }
  context.startActivity(intent)
  ```
  When `context` is a `BroadcastReceiver` (`SmsReceiver`), Android silently suppresses this call. Logcat will show:
  `W/ActivityTaskManager: Background activity start [com.epialert.app] to com.epialert.app/.ui.AlertActivity denied`

### Cause 2: SYSTEM_ALERT_WINDOW Alone Does Not Exempt `startActivity()` in Android 12+
- While `SYSTEM_ALERT_WINDOW` ("Display over other apps") allows drawing **floating overlay views** directly onto the window manager (`TYPE_APPLICATION_OVERLAY`), Google removed the blanket exemption for `startActivity()` from background services starting in Android 12 unless specific launcher tokens exist.

### Cause 3: Conflicting Dual Notifications Neutralize Full-Screen Intent (FSI)
- In `AlarmDispatcher.triggerAlarm()`:
  1. `startAlarmService(context, alert)` starts `AlarmForegroundService`, which posts Notification ID `9002` on `CHANNEL_ID_EMERGENCY`. **Notification 9002 has NO `fullScreenIntent`.**
  2. `postFullScreenNotification(context, alert)` posts Notification ID `9001` on the same channel, which **DOES** have `fullScreenIntent`.
- When two high-priority notifications hit the same channel almost simultaneously, Android's NotificationManager suppresses heads-up / full-screen launches from subsequent notifications to prevent notification spamming.
- In Android 14 (API 34), `USE_FULL_SCREEN_INTENT` is also a special app access permission that can be blocked or revoked in Settings -> Special App Access -> Full-screen intents.

### Cause 4: Android Lock Screen vs. Unlocked Behavior for FSI
- By Android OS design, a Full-Screen Intent (FSI) notification:
  - When phone is **Locked / Screen Off**: launches the activity over the lock screen (if channel is `IMPORTANCE_HIGH`/`MAX` and flags are correct).
  - When phone is **Unlocked**: Android intentionally down-ranks FSI to a **Heads-Up Notification (HUN)** banner at the top of the screen so it doesn't disrupt user input, unless launched via special calling APIs.

### Cause 5: OEM Custom ROM Restrictions (MIUI, ColorOS, OxygenOS, OneUI)
- Chinese and custom OEM Android builds have extra proprietary permissions:
  - Xiaomi/MIUI: *"Display pop-up windows while running in the background"* and *"Show on Lock screen"* (both default to OFF).
  - Vivo/Funtouch: *"Background pop-ups"*.
  - Samsung: Extra battery optimization / sleeping apps sleep rules.

---

## 3. How Other Apps Solve This (e.g. MyGate, WhatsApp, PagerDuty)

### Strategy A: Android Telecom Framework (`ConnectionService` / Incoming Call)
- **Used by**: WhatsApp, Google Meet, Truecaller, MyGate (Gatekeeper call).
- **How it works**: They register an incoming VoIP call via `TelecomManager.addNewIncomingCall()`.
- **Advantage**: The Android OS treats it as a phone call. The OS itself launches the incoming call full-screen activity over the lock screen and over any app unconditionally. No BAL restrictions apply.

### Strategy B: Direct WindowManager Overlay (`TYPE_APPLICATION_OVERLAY`)
- **Used by**: Truecaller Caller ID, Facebook Messenger Chat Heads, emergency alert apps.
- **How it works**: Using `Settings.canDrawOverlays(context)` (`SYSTEM_ALERT_WINDOW`), the app does NOT call `startActivity()`. Instead, a background Service directly inflates the XML view and adds it to `windowManager.addView(overlayView, params)`.
- **Advantage**: 100% bypasses all BAL restrictions. Pops up instantly over any app or home screen.

### Strategy C: Unified Foreground Service Full-Screen Intent Notification
- **Used by**: Android Clock/Alarm app, Google Clock.
- **How it works**:
  - Consolidate into a single notification (`startForeground` in `AlarmForegroundService` with `setFullScreenIntent()`).
  - Set `category = CATEGORY_ALARM`.
  - Pass `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on the `PendingIntent`.
  - In `AlertActivity`, call `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and `keyguardManager.requestDismissKeyguard()`.

---

## 4. Concrete Roadmap for Next Session

When returning to fix this issue, follow this checklist in order:

1. **Fix Notification Collision (Immediate)**:
   - Move `setFullScreenIntent(fullScreenPi, true)` directly into `AlarmForegroundService.startForegroundWithNotification()`.
   - Eliminate the duplicate Notification ID `9001` vs `9002` race condition.

2. **Add `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on the Service's FSI PendingIntent**:
   - Ensure the PendingIntent attached to the Foreground Service notification explicitly allows background activity starts for Android 12, 13, and 14.

3. **Implement WindowManager Overlay Fallback**:
   - If `Settings.canDrawOverlays(context)` is granted, have a service component able to pop up an overlay view immediately if `startActivity` fails or when the device is unlocked.

4. **Add Lock Screen Flags to `AlertActivity` in `onCreate()`**:
   - Verify `setShowWhenLocked(true)` and `setTurnScreenOn(true)` are called **before** `super.onCreate()` or immediately after `setContentView()`.
   - Ensure `R.style.Theme_EpiAlert_Alert` has `<item name="android:windowShowWhenLocked">true</item>` and `<item name="android:windowTurnScreenOn">true</item>`.

5. **Verify OEM Permissions on the Test Device**:
   - In phone Settings -> Apps -> EpiAlert:
     - Allow "Display over other apps"
     - Allow "Alarms & Reminders"
     - In "Special App Access" -> "Full screen intent notifications" -> EpiAlert = Allowed
     - (If Xiaomi/OnePlus/Vivo) Enable "Display pop-up windows while running in the background" and "Show on Lock screen".
