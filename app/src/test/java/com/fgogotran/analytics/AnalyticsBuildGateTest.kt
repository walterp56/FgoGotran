package com.fgogotran.analytics

import com.fgogotran.BuildConfig
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Build/source wiring checks, not a claim of Android device-level verification. */
class AnalyticsBuildGateTest {
    @Test fun `compiled flag follows the owner setting for debug and release`() {
        val repositoryRoot = listOf(File("."), File("..")).first { File(it, "app/build.gradle.kts").isFile }
        val ownerFile = File(repositoryRoot, "analytics-release.properties")
        val properties = Properties().apply {
            if (ownerFile.isFile) ownerFile.reader(Charsets.UTF_8).use { load(it) }
        }
        val expected = properties.getProperty("awsUsageAnalyticsEnabled")?.trim() == "true"
        assertEquals(expected, BuildConfig.AWS_USAGE_ANALYTICS_ENABLED,
            "Rebuild after changing the owner setting; an already-built APK cannot change its flag.")
    }

    @Test fun `every reporting entry returns before collecting or queueing when off`() {
        val analytics = source("app/src/main/java/com/fgogotran/analytics/AppAnalytics.kt")
        for (method in listOf("reportAppForeground", "reportAppProfile", "reportTranslationDisplayed",
            "reportVoicePlaybackStarted", "recordSuccess")) {
            assertTrue(Regex("fun $method\\([^\\n]*\\) \\{\\s*if \\(!BuildConfig\\.AWS_USAGE_ANALYTICS_ENABLED\\) return")
                .containsMatchIn(analytics), "$method must guard before doing any work")
        }
        for (property in listOf("httpClient", "reportingScope", "v2Uploader", "recordings")) {
            assertTrue(analytics.contains("private val $property by lazy"), "$property must not start while OFF")
        }
        val init = analytics.substringAfter("    init {").substringBefore("    /** Called only")
        assertTrue(init.indexOf("if (BuildConfig.AWS_USAGE_ANALYTICS_ENABLED)") < init.indexOf("reportingScope.launch"))
        assertTrue(init.indexOf("reportingScope.launch") < init.indexOf("settingsRepository.updateAnalyticsOutbox"))
        assertFalse(analytics.contains("clearAnalytics"))
        assertFalse(analytics.contains("readAnalyticsOutbox"))
    }

    @Test fun `all build types share one owner choice and missing configuration defaults off`() {
        val gradle = source("app/build.gradle.kts")
        val choice = gradle.substringBefore("android {")
        assertTrue(choice.contains("file(\"analytics-release.properties\")"))
        assertTrue(choice.contains("null, \"false\" -> false"))
        assertTrue(choice.contains("\"true\" -> true"))
        assertTrue(choice.contains("else -> throw GradleException"))
        assertFalse(choice.contains("gradleProperty("))
        assertFalse(choice.contains("environmentVariable("))
        assertFalse(choice.contains("System.getenv("))
        val default = gradle.substringAfter("defaultConfig {").substringBefore("ndk {")
        assertTrue(default.contains("val analyticsEnabled = ownerAnalyticsEnabled.toString()"))
        assertTrue(default.contains("buildConfigField(\"boolean\", \"AWS_USAGE_ANALYTICS_ENABLED\", analyticsEnabled)"))
        assertTrue(default.contains("manifestPlaceholders[\"awsUsageAnalyticsEnabled\"] = analyticsEnabled"))
        val buildTypes = gradle.substringAfter("buildTypes {").substringBefore("compileOptions {")
        assertFalse(buildTypes.contains("AWS_USAGE_ANALYTICS_ENABLED"))
        assertFalse(buildTypes.contains("awsUsageAnalyticsEnabled"))
        assertFalse(buildTypes.contains("ownerAnalyticsEnabled"))
        assertTrue(gradle.contains("buildConfig = true"))
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\"com.fgogotran.AWS_USAGE_ANALYTICS_ENABLED\""))
        assertTrue(manifest.contains("android:value=\"\${awsUsageAnalyticsEnabled}\""))
        assertTrue(source(".gitignore").lineSequence().any { it == "/analytics-release.properties" })
    }

    private fun source(path: String) = listOf(File(path), File("../$path"))
        .first(File::isFile).readText().replace("\r\n", "\n")
}
