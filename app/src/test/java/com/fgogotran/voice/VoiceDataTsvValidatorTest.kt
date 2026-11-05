package com.fgogotran.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoiceDataTsvValidatorTest {
    @Test
    fun `accepts legacy four column name map header`() {
        val file = writeTempFile(
            "jp_name\tcn_name_simp\tcn_name_trad\tcount",
            "マシュ\t玛修\t瑪修\t40126"
        )

        assertEquals(1, validateNameMap(file, expectedCount = 1))
    }

    @Test
    fun `accepts five column name map header with english names`() {
        val file = writeTempFile(
            "jp_name\tcn_name_simp\tcn_name_trad\ten_name\tcount",
            "マシュ\t玛修\t瑪修\tMash\t40126",
            "フォウ\t芙芙\t芙芙\tFou\t2923"
        )

        assertEquals(2, validateNameMap(file, expectedCount = 2))
    }

    @Test
    fun `allows blank english name`() {
        val file = writeTempFile(
            "jp_name\tcn_name_simp\tcn_name_trad\ten_name\tcount",
            "？？？\t？？？\t？？？\t\t8281"
        )

        assertEquals(1, validateNameMap(file, expectedCount = 1))
    }

    @Test
    fun `rejects kana in english name`() {
        val file = writeTempFile(
            "jp_name\tcn_name_simp\tcn_name_trad\ten_name\tcount",
            "マシュ\t玛修\t瑪修\tマシュ\t40126"
        )

        assertFailsWith<IllegalArgumentException> {
            validateNameMap(file, expectedCount = 1)
        }
    }

    @Test
    fun `rejects unknown header shape`() {
        val file = writeTempFile(
            "jp_name\tcn_name_simp\tcn_name_trad\ten_name\tcount\textra",
            "マシュ\t玛修\t瑪修\tMash\t40126\t1"
        )

        assertFailsWith<IllegalArgumentException> {
            validateNameMap(file, expectedCount = 1)
        }
    }

    @Test
    fun `rejects row count mismatch`() {
        val file = writeTempFile(
            "jp_name\tcn_name_simp\tcn_name_trad\ten_name\tcount",
            "マシュ\t玛修\t瑪修\tMash\t40126"
        )

        assertFailsWith<IllegalArgumentException> {
            validateNameMap(file, expectedCount = 2)
        }
    }

    private fun validateNameMap(file: File, expectedCount: Int): Int {
        return VoiceDataTsvValidator.validateFile(
            file = file,
            expectedHeaders = VoiceDataFiles.NAME_MAP_HEADERS,
            expectedMinColumns = VoiceDataFiles.NAME_MAP_MIN_COLUMNS,
            expectedCount = expectedCount,
            validateRow = VoiceDataTsvValidator::validateNameMapRow
        )
    }

    private fun writeTempFile(vararg lines: String): File {
        val file = File.createTempFile("voice-name-map-", ".tsv")
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        file.deleteOnExit()
        return file
    }
}
