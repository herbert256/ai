# Gson: keep data classes used for (de)serialization and their generic signatures
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.JsonAdapter <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.JsonDeserializer
-keep class * extends com.google.gson.JsonSerializer
-keep class * extends com.google.gson.TypeAdapter

# App data models reflected by Gson
-keep class com.ai.data.** { *; }
-keep class com.ai.model.** { *; }

# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit 2 — keep service interfaces and their methods
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# Keep enum names used in data models
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
