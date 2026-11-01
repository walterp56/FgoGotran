package com.fgogotran.chapter.architecture

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source and manifest checks complement the one-way Gradle dependency boundary. */
class ChapterIsolationTest {
    private val moduleDirectory: File
        get() = File(requireNotNull(System.getProperty("chapter.projectDir")) {
            "The chapter test task must provide its module directory"
        })

    private fun foreignFeatureReferences(text: String): List<String> =
        Regex("""\bcom\.fgogotran(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
            .findAll(text)
            .map { it.value }
            .filterNot { it == "com.fgogotran.chapter" || it.startsWith("com.fgogotran.chapter.") }
            .distinct()
            .toList()

    @Test
    fun `production source cannot reference another feature even by qualified name`() {
        val sourceDirectory = File(moduleDirectory, "src/main/java")
        val sources = sourceDirectory.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .toList()
        assertTrue(sources.isNotEmpty(), "The guard must inspect real production source files")
        sources.forEach { source ->
            assertEquals(
                emptyList(),
                foreignFeatureReferences(source.readText()),
                "${source.relativeTo(moduleDirectory)} references code outside Chapter"
            )
            assertFalse(source.readText().contains("dagger.hilt"), "Chapter must not install global Hilt bindings")
        }
    }

    @Test
    fun `reference guard rejects app internals and similarly prefixed packages`() {
        assertEquals(
            listOf(
                "com.fgogotran.translation.Translator",
                "com.fgogotran.ocr.OcrEngine",
                "com.fgogotran.data.SettingsRepository",
                "com.fgogotran.analytics.AppAnalytics",
                "com.fgogotran.chapterbridge.Host"
            ),
            foreignFeatureReferences(
                """
                import com.fgogotran.translation.Translator
                val engine: com.fgogotran.ocr.OcrEngine
                import com.fgogotran.data.SettingsRepository
                Class.forName("com.fgogotran.analytics.AppAnalytics")
                import com.fgogotran.chapterbridge.Host
                """.trimIndent()
            )
        )
        assertTrue(foreignFeatureReferences("import com.fgogotran.chapter.asset.Codec").isEmpty())
    }

    @Test
    fun `browser manifest allows only the protected Shizuku provider and package visibility`() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            // Android's compile stubs omit these JAXP constants; the JVM test parser
            // still supports their standard property URIs.
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val manifest = factory.newDocumentBuilder()
            .parse(File(moduleDirectory, "src/main/AndroidManifest.xml"))
        assertEquals("manifest", manifest.documentElement.tagName)
        listOf(
            "uses-permission", "uses-permission-sdk-23", "activity",
            "activity-alias", "service", "receiver", "instrumentation"
        ).forEach { tag ->
            assertEquals(0, manifest.getElementsByTagName(tag).length, "The read-only browser must not add $tag")
        }
        val ns = "http://schemas.android.com/apk/res/android"
        val app = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("", app.getAttributeNS(ns, "name"), "No global Chapter Application")
        assertEquals(1, manifest.getElementsByTagName("provider").length)
        val provider = manifest.getElementsByTagName("provider").item(0) as org.w3c.dom.Element
        assertEquals("rikka.shizuku.ShizukuProvider", provider.getAttributeNS(ns, "name"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.getAttributeNS(ns, "permission"))
        assertEquals("false", provider.getAttributeNS(ns, "multiprocess"))
        assertEquals("true", provider.getAttributeNS(ns, "exported"))
        assertEquals("\${applicationId}.shizuku", provider.getAttributeNS(ns, "authorities"))
        assertEquals(0, manifest.getElementsByTagName("meta-data").length)
    }

    @Test
    fun `app packages Chapter through a thin non Hilt presentation host only`() {
        val appBuild = File(moduleDirectory.parentFile, "app/build.gradle.kts").readText()
        assertTrue(
            Regex("""project\s*\(\s*(?:path\s*=\s*)?["']:chapter["']""").containsMatchIn(appBuild),
            "The approved browser must have its explicit one-way host edge"
        )
        val host = File(moduleDirectory.parentFile, "app/src/main/java/com/fgogotran/ChapterHostActivity.kt").readText()
        listOf("dagger", "SettingsRepository", "Translator", "FgoAccessibilityService", "FgoRunnerService", "AppAnalytics").forEach {
            assertFalse(host.contains(it), "The host must not integrate $it")
        }
        assertTrue(host.contains("ChapterScreen(onExit"))
    }

    @Test
    fun `privileged IPC exposes no write install delete or arbitrary command API`() {
        val aidl = File(moduleDirectory, "src/main/aidl/com/fgogotran/chapter/storage/IChapterFileService.aidl").readText()
        val methods = Regex("""\b(?:int|String|void|ParcelFileDescriptor)\s+(\w+)\(""").findAll(aidl).map { it.groupValues[1] }.toList()
        assertEquals(listOf("getServiceUid", "openOriginal", "fingerprint", "destroy"), methods)
        val service = File(moduleDirectory, "src/main/java/com/fgogotran/chapter/storage/ChapterFileService.kt").readText()
        assertTrue(service.contains("OsConstants.O_RDONLY"))
        assertTrue(service.contains("OsConstants.O_NOFOLLOW"))
        listOf("O_WRONLY", "O_RDWR", "O_CREAT", "Runtime.getRuntime", "ProcessBuilder").forEach { assertFalse(service.contains(it)) }
    }

    @Test
    fun `installer is separate restricted IPC without arbitrary paths commands or fault injection`() {
        val aidl = File(moduleDirectory, "src/main/aidl/com/fgogotran/chapter/storage/IChapterInstallService.aidl").readText()
        val methods = Regex("""\b(?:String|void)\s+(\w+)\(""").findAll(aidl).map { it.groupValues[1] }.toList()
        assertEquals(listOf("environment", "inspect", "stage", "commit", "restore", "inspectKnown", "restoreKnown", "destroy"), methods)
        assertFalse(aidl.contains("String path")); assertFalse(aidl.contains("String command"))
        assertFalse(aidl.contains("crash", ignoreCase = true))
        val service = File(moduleDirectory, "src/main/java/com/fgogotran/chapter/storage/ChapterInstallService.kt").readText()
        assertTrue(service.contains("Os.getuid() == 2000")); assertTrue(service.contains("Binder.getCallingUid() == ownerUid"))
        assertTrue(service.contains("OsConstants.O_NOFOLLOW")); assertTrue(service.contains("OsConstants.O_EXCL"))
        assertTrue(service.contains("ProcessBuilder(\"/system/bin/ps\", \"-A\", \"-n\", \"-o\", \"UID,PID,NAME\")"))
        listOf("/system/bin/sh", "Runtime.getRuntime", "force-stop", "failAfterReplace", "failBeforeReplace").forEach { assertFalse(service.contains(it)) }
        assertEquals(5, Regex("ChapterInstallPolicy::requireOriginals").findAll(service).count())
        assertTrue(aidl.contains("in ParcelFileDescriptor originalManifest"))
        assertTrue(aidl.contains("in ParcelFileDescriptor preparedManifest"))
        assertTrue(clientUsesOnlyReadOnlyReferences())
        assertTrue(service.contains("OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0"))
        assertTrue(service.contains("Os.fstat(fd).identity() == expected && Os.lstat(target.path).identity() == expected"))
        assertTrue(service.contains("it.requireGameAccess(gameUid)"))
        assertTrue(service.contains("Os.pwrite(fd")); assertTrue(service.contains("Os.ftruncate(fd"))
        assertFalse(service.contains("Os.rename")); assertFalse(service.contains("Os.fchmod"))
        assertFalse(service.contains("Os.chown")); assertFalse(service.contains("Os.fchown"))
        val client = File(moduleDirectory, "src/main/java/com/fgogotran/chapter/storage/ShizukuChapterInstaller.kt").readText()
        assertTrue(client.contains(".daemon(false)")); assertFalse(client.contains(".daemon(true)"))
        val detach = client.indexOf("Shizuku.unbindUserService(args, it, false)")
        val destroy = client.indexOf("Shizuku.unbindUserService(args, it, true)")
        assertTrue(detach >= 0 && destroy > detach, "Clear Shizuku's cached callbacks before destroying the service")
    }
    private fun clientUsesOnlyReadOnlyReferences(): Boolean {
        val client = File(moduleDirectory, "src/main/java/com/fgogotran/chapter/storage/ShizukuChapterInstaller.kt").readText()
        return client.contains("ParcelFileDescriptor.MODE_READ_ONLY") && !client.contains("ParcelFileDescriptor.MODE_WRITE")
    }
}
