import java.io.StringReader
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// OWNER-CONTROLLED: AI must never create, edit, reset or override the real
// analytics-release.properties setting (including via commands/environment).
// Missing configuration is OFF; no build type may enable it implicitly.
val analyticsReleaseProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("analytics-release.properties"))
        .asText.orNull?.let { load(StringReader(it)) }
}
val ownerAnalyticsEnabled = when (analyticsReleaseProperties.getProperty("awsUsageAnalyticsEnabled")?.trim()) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("awsUsageAnalyticsEnabled must be true or false in the owner-controlled analytics-release.properties. Ask the owner to correct it; AI must not change it.")
}

android {
    namespace = "com.fgogotran"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fgogotran"
        minSdk = 30
        targetSdk = 34
        versionCode = 17
        versionName = "4.1.1"

        // Run/Debug and release share the owner's local choice and both APK markers.
        val analyticsEnabled = ownerAnalyticsEnabled.toString()
        buildConfigField("boolean", "AWS_USAGE_ANALYTICS_ENABLED", analyticsEnabled)
        manifestPlaceholders["awsUsageAnalyticsEnabled"] = analyticsEnabled

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties"
        )
    }
}

dependencies {
    // Compose BOM
    // Must stay on a Compose UI 1.7.x BOM (2024.09.00 or newer): from Compose UI 1.7.0 the
    // lifecycle-compose LocalLifecycleOwner is provided directly, so lifecycle 2.8.x stops
    // needing its reflection bridge into Compose UI 1.6.x, which R8 removed in release builds
    // and which crashed startup with "CompositionLocal LocalLifecycleOwner not present".
    // Compose 1.7.x requires compileSdk 34, so this needs no SDK bump.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // 2.8.0-2.8.2 are the versions where the Compose UI 1.6 reflection bridge was broken by R8.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ML Kit OCR (bundled so OCR works without Google Play services/model delivery)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // PaddleOCR PP-OCRv6 ONNX runtime and polygon post-processing
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.locationtech.jts:jts-core:1.19.0")

    // Ktor HTTP client
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Azure Speech SDK for low-latency Japanese -> Chinese streaming translation
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.51.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // AppCompat (for AlertDialog from Service context)
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Recycle the existing native bilingual LOG rows; 1.3.2 supports compileSdk 34.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(kotlin("test"))

}
