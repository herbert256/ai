# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep API models (Retrofit/Gson serialization)
-keep class com.ai.data.** { *; }
-keep class com.ai.ui.*Export { *; }
-keep class com.ai.ui.*Export$* { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
