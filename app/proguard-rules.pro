# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }

# Keep ML Kit classes
-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }

# Keep MNN (Mobile Neural Network) framework classes
-keep class com.alibaba.mnn.** { *; }
-keep interface com.alibaba.mnn.** { *; }

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# Keep Plugin interface and all implementations
-keep interface com.tronprotocol.app.plugins.Plugin { *; }
-keep class com.tronprotocol.app.plugins.** implements com.tronprotocol.app.plugins.Plugin { *; }

# Keep JSON model classes used with reflection
-keep class com.tronprotocol.app.models.** { *; }
-keepclassmembers class com.tronprotocol.app.models.** {
    <fields>;
    <init>(...);
}
