package com.fgogotran.translation

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.TermEntity
import com.fgogotran.text.EmphasisRange
import com.fgogotran.text.EmphasisText
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only DB benchmark and pure fixtures; never launches FGO or changes settings/glossary. */
class GlossaryOcrDeviceTest {
    @Test fun nativeCorrectionsPreserveAnnotationsAndStoredNameGender() {
        val row = CharacterNameEntity(jpName = "ニキチッチ", cnName = "尼基季奇", enName = "Nikitich", gender = "女性")
        val term = TermEntity(jpTerm = row.jpName, cnTerm = row.cnName, enTerm = row.enName, category = "character", gender = row.gender)
        val corrector = GlossaryOcrCorrector(listOf(row), listOf(term))
        val fixed = corrector.correctName("二キチッチ")
        assertEquals("尼基季奇", NameBoxLookupPolicy.findTranslation(fixed.text, listOf(row), false))
        assertEquals("Nikitich", NameBoxLookupPolicy.findTranslation(fixed.text, listOf(row), true))
        val annotated = EmphasisText.encode("二キチッチ〈二キチッチ〉！", listOf(EmphasisRange(0, 5)))
        val changed = corrector.correctText(annotated)
        assertEquals("ニキチッチ〈二キチッチ〉！", EmphasisText.plain(changed.text))
        assertEquals(EmphasisText.decode(annotated).ranges, EmphasisText.decode(changed.text).ranges)
        for (literal in listOf("二人", "第二", "魔力", "工房", "入口", "二キチッチ（偽）")) {
            assertFalse(corrector.correctName(literal).changed)
        }
    }

    @Test fun measureReadOnlyInstalledDictionaryColdAndWarmCosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = context.getDatabasePath("fgo_terms.db")
        assumeTrue("installed glossary required for device benchmark", database.isFile)
        val names = mutableListOf<CharacterNameEntity>()
        val terms = mutableListOf<TermEntity>()
        SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT jp_name,cn_name,en_name,gender,aliases FROM character_names", null).use { rows ->
                while (rows.moveToNext()) names += CharacterNameEntity(jpName = rows.getString(0), cnName = rows.getString(1),
                    enName = rows.getString(2).orEmpty(), gender = rows.getString(3).orEmpty(), aliases = rows.getString(4))
            }
            db.rawQuery("SELECT jp_term,cn_term,en_term,category,gender,aliases FROM terms", null).use { rows ->
                while (rows.moveToNext()) terms += TermEntity(jpTerm = rows.getString(0), cnTerm = rows.getString(1),
                    enTerm = rows.getString(2).orEmpty(), category = rows.getString(3), gender = rows.getString(4).orEmpty(), aliases = rows.getString(5))
            }
        }
        val allTerms = mergeCachedGlossaryTerms(terms, names.map { TermEntity(jpTerm = it.jpName, cnTerm = it.cnName,
            enTerm = it.enName, category = "character", gender = it.gender, aliases = it.aliases) })
        val start = SystemClock.elapsedRealtimeNanos()
        val corrector = GlossaryOcrCorrector(names, allTerms)
        val coldMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val report = StringBuilder("names=${names.size}, terms=${allTerms.size}, coldMs=$coldMs\n")
        for (length in listOf(20, 80, 200)) {
            val source = "あ".repeat(length - 6) + "二キチッチ。"
            assertEquals(length, source.length)
            assertTrue(corrector.correctText(source).changed)
            repeat(300) { corrector.correctText(source) }
            val samples = LongArray(1_000) {
                val before = SystemClock.elapsedRealtimeNanos()
                corrector.correctText(source)
                SystemClock.elapsedRealtimeNanos() - before
            }.sorted()
            val median = samples[500] / 1_000_000.0
            val p95 = samples[949] / 1_000_000.0
            report.append("length=$length, iterations=1000, medianMs=$median, p95Ms=$p95, targetMet=${p95 <= 2.0}\n")
        }
        Log.i("FGO/GlossaryOcrBench", report.toString())
        File(context.getExternalFilesDir(null), "glossary-ocr-benchmark.txt").writeText(report.toString())
    }
}
