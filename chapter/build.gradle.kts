import org.gradle.api.artifacts.ProjectDependency

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Catalogue endpoint; override with -Pchapter.nativeBaseUrl / -Pchapter.nativeApiKey.
val nativeBaseUrl = providers.gradleProperty("chapter.nativeBaseUrl")
    .orElse("https://v4qqn1rvi1.execute-api.ap-east-1.amazonaws.com/prod")
    .get()
val nativeApiKey = providers.gradleProperty("chapter.nativeApiKey").orElse("").get()

android {
    namespace = "com.fgogotran.chapter"
    compileSdk = 34
    resourcePrefix = "chapter_"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "NATIVE_BASE_URL", "\"$nativeBaseUrl\"")
        buildConfigField("String", "NATIVE_API_KEY", "\"$nativeApiKey\"")
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("chapter.projectDir", projectDir.absolutePath)
            it.systemProperty("chapter.localCapture", providers.gradleProperty("chapter.localCapture").orNull ?: "")
            it.systemProperty("chapter.localStorySources", providers.gradleProperty("chapter.localStorySources").orNull ?: "")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Explicit lightweight primitives only: no JVM security-provider registration.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.tukaani:xz:1.12")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// The feature owns its implementation. Including this module must not pull the existing
// screen-recognition, speech, dependency-injection or app modules into its classpath.
val verifyChapterDependencies by tasks.registering {
    group = "verification"
    description = "Checks that Chapter has no app/project or recognition/speech dependencies."
    doLast {
        val declaredDependencies = configurations.flatMap { it.dependencies }.distinct()
        // AGP adds a self-project edge for the library's test components. It does not
        // expose another feature and must not be confused with an app dependency.
        val projectDependencies = declaredDependencies.filterIsInstance<ProjectDependency>()
            .filter { it.path != project.path }
        check(projectDependencies.isEmpty()) {
            "Chapter must not depend on another project: $projectDependencies"
        }
        val forbiddenGroups = listOf(
            "com.google.mlkit",
            "com.microsoft.onnxruntime",
            "com.microsoft.cognitiveservices.speech",
            "com.google.dagger"
        )
        val forbiddenDependencies = declaredDependencies.filter { dependency ->
            forbiddenGroups.any { group ->
                dependency.group == group || dependency.group?.startsWith("$group.") == true
            }
        }
        check(forbiddenDependencies.isEmpty()) {
            "Chapter must not depend on recognition, speech or global DI: $forbiddenDependencies"
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyChapterDependencies)
}

tasks.named("check") {
    dependsOn(verifyChapterDependencies)
}

// Desktop-only entry point shares the APK's schema/archive validator. The main class
// is in test/tool sources and is not included in the Android library or application.
tasks.register<JavaExec>("publishLocalStoryPack") {
    group = "story assets"
    description = "Publishes a reviewed local source-language pack; never accesses a device or network."
    dependsOn("compileDebugUnitTestKotlin", verifyChapterDependencies)
    mainClass.set("com.fgogotran.chapter.packs.tools.StoryPackPublisherMain")
    doFirst {
        val draft = providers.gradleProperty("chapter.storyPackDraft").orNull
            ?: throw GradleException("Specify -Pchapter.storyPackDraft=<directory below story_assets/mappings>")
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
        args(rootProject.projectDir.absolutePath, draft)
    }
}

// R2 developer tooling lives in test sources and cannot initialise from the APK.
tasks.register<JavaExec>("discoverAtlasStorySources") {
    group = "story assets"
    description = "Fetches bounded immutable Atlas regional sources; no device or installation access."
    dependsOn("compileDebugUnitTestKotlin", verifyChapterDependencies)
    mainClass.set("com.fgogotran.chapter.prepare.NativePreparationMain")
    doFirst {
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
        args("discover", rootProject.projectDir.absolutePath,
            providers.gradleProperty("chapter.atlasWars").orElse("100").get(),
            providers.gradleProperty("chapter.atlasQuests").orElse("-").get())
    }
}

tasks.register<JavaExec>("buildNativeStoryCandidate") {
    group = "story assets"
    description = "Builds quarantined cumulative native candidates offline; does not publish or install."
    dependsOn("compileDebugUnitTestKotlin", verifyChapterDependencies)
    mainClass.set("com.fgogotran.chapter.prepare.NativePreparationMain")
    doFirst {
        fun required(name: String) = providers.gradleProperty(name).orNull ?: throw GradleException("Specify -P$name=<reviewed local directory>")
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
        args("build", rootProject.projectDir.absolutePath, required("chapter.atlasSnapshot"), required("chapter.nativeCapture"),
            providers.gradleProperty("chapter.nativeLanguage").orElse("all").get(),
            providers.gradleProperty("chapter.reviewedAlignments").orElse("-").get())
    }
}

tasks.register<JavaExec>("buildFullBundlePack") {
    group = "story assets"
    description = "Aligns every script in one captured bundle against the cloud store; writes coverage and bundle."
    dependsOn("compileDebugUnitTestKotlin", verifyChapterDependencies)
    mainClass.set("com.fgogotran.chapter.prepare.FullBundleMain")
    doFirst {
        fun required(name: String) = providers.gradleProperty(name).orNull ?: throw GradleException("Specify -P$name=<path>")
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath +
            files("$buildDir/tmp/kotlin-classes/debug", "$buildDir/tmp/kotlin-classes/debugUnitTest")
        args(rootProject.projectDir.absolutePath, required("chapter.capture"), required("chapter.region"),
            required("chapter.cloud"), required("chapter.out"), providers.gradleProperty("chapter.bundle").orElse("").get())
    }
}
