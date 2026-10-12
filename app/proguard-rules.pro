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

# Lifecycle 2.8 + Compose UI 1.6
# lifecycle-runtime-compose 2.8.x resolves its LocalLifecycleOwner by reflecting on
# androidx.compose.ui.platform.AndroidCompositionLocals_androidKt.getLocalLifecycleOwner()
# when it detects Compose UI 1.6.*. R8 inlines that getter into its Kotlin callers and then
# deletes the method, so getMethod() throws NoSuchMethodException, the library falls back to
# its throwing default, and every read of LocalLifecycleOwner (collectAsStateWithLifecycle
# first) dies with "CompositionLocal LocalLifecycleOwner not present" on the release build only.
# The library is supposed to ship this rule; ship it here so shrinking cannot remove the target.
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static *** getLocalLifecycleOwner();
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ONNX Runtime
# Native JNI code looks up these Java classes by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Azure Speech SDK
# Native JNI code creates and calls these Java types by name.
-keep class com.microsoft.cognitiveservices.speech.** { *; }
-dontwarn com.microsoft.cognitiveservices.speech.**
-dontwarn io.micrometer.context.ContextAccessor
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
