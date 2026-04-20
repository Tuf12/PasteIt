# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# FFmpeg Kit (JNI)
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**

# mp3agic
-keep class com.mpatric.mp3agic.** { *; }

# AndroidX Security Crypto (MasterKey / EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# CommonMark / autolink (if used reflectively)
-keep class org.commonmark.** { *; }
