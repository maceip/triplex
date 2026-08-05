# R8 full mode rules for Triplex Dialer App

# Keep Kotlinx Serialization classes
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keep class * implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep Triplex engine classes (audio pipeline, echo cancellation, etc.)
-keep class com.triplex.dialer.triplex.** { *; }
-keepclassmembers class com.triplex.dialer.triplex.** { *; }

# Keep model classes used with kotlinx.serialization
-keep class com.triplex.dialer.model.** { *; }
-keepclassmembers class com.triplex.dialer.model.** { *; }

# Keep Compose-related classes
-keep class androidx.compose.** { *; }
