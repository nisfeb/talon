# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class io.nisfeb.talon.**$$serializer { *; }
-keepclassmembers class io.nisfeb.talon.** {
    *** Companion;
}
-keepclasseswithmembers class io.nisfeb.talon.** {
    kotlinx.serialization.KSerializer serializer(...);
}
