import org.gradle.api.artifacts.ProjectDependency

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fgogotran.chapter"
    compileSdk = 34
    resourcePrefix = "chapter_"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("chapter.projectDir", projectDir.absolutePath)
            it.systemProperty("chapter.localCapture", providers.gradleProperty("chapter.localCapture").orNull ?: "")
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
