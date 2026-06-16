# DualFrame ProGuard Rules

-keepattributes *Annotation*

# Google Play Billing
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }

# AdMob
-keep class com.google.android.gms.ads.** { *; }

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# Compose (needed for R8 compatibility)
-dontwarn androidx.compose.**
