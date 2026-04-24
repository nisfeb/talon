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
