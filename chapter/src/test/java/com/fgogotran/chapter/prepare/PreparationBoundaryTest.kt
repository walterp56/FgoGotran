package com.fgogotran.chapter.prepare

import java.io.File
import kotlin.test.*

class PreparationBoundaryTest {
    @Test fun `R2 orchestration and acquisition never enter production APK or installer`() {
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        assertFalse(File(module, "src/main/java/com/fgogotran/chapter/prepare").exists())
        val sources = File(module, "src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml", "aidl") }
        sources.forEach { file -> assertFalse(file.readText().contains("com.fgogotran.chapter.prepare"), file.name) }
        val tools = listOf("AtlasSnapshot.kt", "NativeCandidateBuilder.kt", "NativeCandidateStore.kt", "NativePreparationMain.kt", "CompleteScriptAlignment.kt", "ReviewedNativeBaseline.kt")
        tools.forEach { name ->
            val text = File(module, "src/test/java/com/fgogotran/chapter/prepare/$name").readText()
            listOf("import android.", "ProcessBuilder", "Runtime.getRuntime", "com.fgogotran.chapter.install",
                "com.fgogotran.chapter.storage", "com.fgogotran.ocr", "com.fgogotran.translation", "com.fgogotran.analytics").forEach {
                assertFalse(text.contains(it), "$name must not acquire $it")
            }
        }
        // Native R1 gate remains outside this developer package; no test sets its proof flags.
        assertFalse(File(module, "src/test/java/com/fgogotran/chapter/prepare/NativePreparationMain.kt").readText().contains("unchangedVerified"))
    }
}
