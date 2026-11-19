# Rivo

-keep class io.github.iseaprojects.rivo.** { *; }
-keepclassmembers class io.github.iseaprojects.rivo.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.iseaprojects.rivo.core.**$$serializer { *; }

# NewPipeExtractor reflects over its service classes.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.grack.nanojson.** { *; }

# youtubedl-android keeps Python inside the APK; its loader is reflective.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Rhino (pulled in by NewPipeExtractor) references desktop-only AWT/Beans/ImageIO
# classes that do not exist on Android. They are never reached on device.
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.imageio.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# Coil
-dontwarn okhttp3.**
-dontwarn okio.**
