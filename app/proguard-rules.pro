# Proguard rules for Nuvio on GEN5W (KitKat API 19)

# Preserve JavascriptInterface methods for WebView bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve reflective automotive framework hooks
-keep class com.hkmc.** { *; }
-dontwarn com.hkmc.**

# Preserve ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

