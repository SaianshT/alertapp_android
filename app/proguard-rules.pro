# Add project specific ProGuard rules here.

# ─── Firebase ──────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ─── EpiAlert model (Parcelable) ───────────────────────────────────────
-keep class com.epialert.app.model.** { *; }

# ─── BroadcastReceivers & Services ─────────────────────────────────────────
-keep class com.epialert.app.receiver.** { *; }
-keep class com.epialert.app.service.**  { *; }

# ─── Kotlin Parcelize ───────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# ─── Kotlin metadata ────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
