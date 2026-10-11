package com.fgogotran.analytics

import com.fgogotran.BuildConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Build/source wiring checks, not a claim of Android device-level verification. */
class AnalyticsBuildGateTest {
    @Test fun `debug is compiled with analytics off`() {
        if (BuildConfig.BUILD_TYPE == "debug") assertFalse(BuildConfig.AWS_USAGE_ANALYTICS_ENABLED)
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

    @Test fun `build defaults off and only owner file can explicitly enable release`() {
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
        val release = gradle.substringAfter("release {").substringBefore("isMinifyEnabled")
        assertTrue(default.contains("val analyticsEnabled = false.toString()"))
        assertTrue(release.contains("val analyticsEnabled = ownerAnalyticsEnabled.toString()"))
        for (block in listOf(default, release)) {
            assertTrue(block.contains("buildConfigField(\"boolean\", \"AWS_USAGE_ANALYTICS_ENABLED\", analyticsEnabled)"))
            assertTrue(block.contains("manifestPlaceholders[\"awsUsageAnalyticsEnabled\"] = analyticsEnabled"))
        }
        assertTrue(gradle.contains("buildConfig = true"))
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\"com.fgogotran.AWS_USAGE_ANALYTICS_ENABLED\""))
        assertTrue(manifest.contains("android:value=\"\${awsUsageAnalyticsEnabled}\""))
        assertTrue(source(".gitignore").lineSequence().any { it == "/analytics-release.properties" })
    }

    private fun source(path: String) = listOf(File(path), File("../$path"))
        .first(File::isFile).readText().replace("\r\n", "\n")
}
