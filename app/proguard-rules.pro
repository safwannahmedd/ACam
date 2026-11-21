# Keep kotlinx.serialization generated serializers (release builds are not minified,
# these rules exist for safety if minification is enabled later).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.acam.**$$serializer { *; }
-keepclassmembers class com.acam.** {
    *** Companion;
}
-keepclasseswithmembers class com.acam.** {
    kotlinx.serialization.KSerializer serializer(...);
}
