# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.fgogotran.**$$serializer { *; }
-keepclassmembers class com.fgogotran.** { *** Companion; }
-keepclasseswithmembers class com.fgogotran.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ONNX Runtime
# Native JNI code looks up these Java classes by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
