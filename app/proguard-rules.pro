# R8 is on for release for speed, not size — see the note on isMinifyEnabled in
# build.gradle.kts. These rules keep it away from everything that finds classes
# by name at runtime, where a missing class shows up as a crash on a device
# rather than as a build error.

# Nothing is renamed. Stack traces stay readable, and every lookup by name —
# enum names in preferences, Media3 custom commands, library reflection — keeps
# working without a rule of its own.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# ---- Logging ------------------------------------------------------------------
# A release build writes nothing to logcat. Every call is removed, ours and every
# library's that goes through android.util.Log — Media3's included — and with it
# the string each one built, on threads like playback's and the analyzer's that
# log several times a second. Nobody reads logcat on a user's phone: what a bug
# report needs is Copy Log in the player, which reads TrackLog's own buffer and
# never touched logcat to begin with (see TrackLog's class doc).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# ---- Lyrics providers ----------------------------------------------------------
# jsoup parses Genius's page HTML — nothing is looked up by name, but keeping it
# whole costs nothing next to auditing every reflective entry point.
-keep class org.jsoup.** { *; }

# ---- Native and crypto --------------------------------------------------------
# ONNX Runtime is called from JNI, which R8 cannot see.
-keep class ai.onnxruntime.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# The vendored FFmpeg extension's JNI calls back into this by name to grow
# its output buffer (native/ffmpeg/ffmpeg_jni.cc, JNI_OnLoad).
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# ---- Serialization --------------------------------------------------------------
# Ktor resolves serializers and engine pieces by type at runtime.
-keep class io.ktor.** { *; }
# Our own @Serializable models, whole: kotlinx.serialization's bundled rules
# cover the generated serializers, and keeping the classes too costs little.
-keep @kotlinx.serialization.Serializable class com.juni.io.** { *; }

# ---- JVM-only APIs ------------------------------------------------------------
# Referenced by Ktor's IntelliJ debugger probe and mbassy's EL filters. None of
# them exist on Android and none of those paths run here — the debug build,
# which R8 never touches, has always lived without them.
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.el.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.ietf.jgss.**
