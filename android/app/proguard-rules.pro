-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.systemutility.**$$serializer { *; }
-keepclassmembers class com.systemutility.** {
    *** Companion;
}
-keepclasseswithmembers class com.systemutility.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep class com.systemutility.core.network.dto.** { *; }
-keep class com.systemutility.core.model.** { *; }

# Signal Protocol & Argon2 JNI
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**
-keep class com.lambdapioneer.argon2kt.** { *; }

# SQLCipher & Room
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**
-keep class * extends androidx.room.RoomDatabase { *; }

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Biometric & Security Crypto (Tink ErrorProne annotations)
-keep class androidx.biometric.** { *; }
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Retrofit / OkHttp
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-keepattributes Exceptions

# Strip verbose and debug log calls in release builds (SafeLogger uses Log.i/w/e)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
