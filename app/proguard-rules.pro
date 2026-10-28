# Aggressive R8 fullMode optimization passes & package flattening for minimal DEX size
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'd'

# Keep reflective ActivityOptions freeform hooks if OEM framework exposes them
-keepclassmembers class android.app.ActivityOptions {
    public void setLaunchWindowingMode(int);
    public void setTaskAlwaysOnTop(boolean);
}

# Silence R8 warnings on hidden AOSP AudioProductStrategy / Telephony APIs
-dontwarn android.media.audiopolicy.**
-dontwarn android.media.AudioDeviceAttributes
-dontwarn android.telephony.**

# Strip verbose, debug & info logging calls in release builds to reduce DEX size and string allocations
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
