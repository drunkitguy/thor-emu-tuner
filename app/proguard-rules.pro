# kotlinx.serialization: keep generated serializers and companion lookups for @Serializable models
# (preset JSON, stored profiles, sessions, settings).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.thoremutuner.**$$serializer { *; }
-keepclassmembers class dev.thoremutuner.** {
    *** Companion;
}
-keepclasseswithmembers class dev.thoremutuner.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class dev.thoremutuner.core.** { *; }

# Preset JSON files are read as Java resources from the :core jar.
-keepdirectories presets
