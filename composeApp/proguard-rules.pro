# Kotlinx serialization
# See https://github.com/Kotlin/kotlinx.serialization#android

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Generated $$serializer classes — always reflected at runtime.
-keep,includedescriptorclasses class **$$serializer { *; }

# Keep Companion classes + their serializer() methods in our own
# package. The conditional `-if @Serializable` rules don't always
# catch nested Companions in R8, so we pin them explicitly.
-keep class io.nisfeb.talon.**$Companion { *; }
-keepclassmembers class io.nisfeb.talon.** {
    *** Companion;
}
-keepclasseswithmembers class io.nisfeb.talon.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Same for kotlinx.serialization.json itself.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose / debug logs in release. Log.w / Log.e / Log.i still
# emit so production diagnostics survive — only Log.d and Log.v get
# inlined-away. Avoids leaking diff payload contents and group
# membership traces into release-mode logcat.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# MediaPipe Tasks Text pulls in autovalue / javapoet which reference
# javax.lang.model — JDK-only, absent on Android. Suppress R8's
# missing-class errors; the references are only used at annotation-
# processing time and never reached at runtime.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**

# MediaPipe Tasks uses protobuf reflection to serialize task options to
# its native C++ runtime — R8 renames the generated protobuf field
# accessors and the cross-language bridge fails at runtime ("Field
# platform_ for u5.E not found"). Keep MediaPipe + protobuf intact.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# MediaPipe transitively pulls in Guava Flogger, which does
# stack-walk caller lookup in static initializers — R8 inlines
# / renames frames and Flogger's CallerFinder fails ("no caller
# found on the stack"). Keep com.google.common.* intact in
# release. Heavy but unavoidable for MediaPipe Tasks.
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-keep class com.google.errorprone.annotations.** { *; }
-dontwarn com.google.errorprone.annotations.**
-keep class j$.** { *; }
-dontwarn j$.**

# Keep every enum in our namespace — including the constant *fields*,
# not just values() / valueOf(). We persist the .name() of provider /
# feature / tab enums in EncryptedSharedPreferences and reload via
# valueOf(); without this rule R8 renames the constants in release
# and the lookup falls back to a default, silently breaking features
# (we shipped a release where OpenRouter keys silently routed to
# Anthropic for exactly this reason).
-keep enum io.nisfeb.talon.** { *; }

# UnifiedPush connector library 3.0.5 ships an empty proguard.txt
# (verified: `unzip -p connector-3.0.5.aar proguard.txt` is 0 bytes).
# In release, R8 renames its internal classes and `getDistributors()`
# silently returns empty — Talon's notification settings then claim
# "no distributor" even when ntfy is installed and configured. Keep
# the whole connector + distributor protocol packages intact, plus
# any subclass of MessagingReceiver in our own code, since the
# distributor IPC handshake names them by reflection.
-keep class org.unifiedpush.android.connector.** { *; }
-keep class org.unifiedpush.android.distributor.** { *; }
-keep class * extends org.unifiedpush.android.connector.MessagingReceiver { *; }
-dontwarn org.unifiedpush.android.**
