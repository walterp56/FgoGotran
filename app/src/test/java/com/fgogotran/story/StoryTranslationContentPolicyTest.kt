package com.fgogotran.story

import com.fgogotran.translation.SceneDialogueContext
import com.fgogotran.translation.SceneTranslateInput
import com.fgogotran.translation.TranslationRubyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoryTranslationContentPolicyTest {
    @Test
    fun `punctuation and symbol-only dialogue cannot request a speaker translation or voice hint`() {
        for (name in listOf("マシュ", "未登録の話者", "？？？", null)) {
            for (text in listOf("……", ".......", "───", "！？", "「……」", "……\n───", "☆♪♡", "🙂", "？？？")) {
                val source = input(text, name = name, voiceHint = true)
                val decision = StoryTranslationContentPolicy.evaluate(source)
                val request = decision.prepare(source)

                assertFalse(decision.hasWork, "$name: $text")
                assertFalse(decision.translateDialogue, text)
                assertNull(request.name)
                assertNull(request.dialogue)
                assertFalse(request.requestVoiceHint)
                assertEquals(text, source.dialogue)
                assertEquals(name, source.name)
            }
        }
    }

    @Test
    fun `real letters digits Japanese dash-like letters and meaningful ruby are retained`() {
        for (text in listOf("一", "ー", "あ", "A", "123", "１２３", "コーヒー", "……本文───", "星見台〈カルデア〉")) {
            val source = input(text)
            val decision = StoryTranslationContentPolicy.evaluate(source)
            assertTrue(decision.hasWork, text)
            assertTrue(decision.translateDialogue, text)
            assertEquals(source, decision.prepare(source))
        }
    }

    @Test
    fun `one meaningful source row preserves the entire dialogue including pauses and explicit newlines`() {
        for (text in listOf("……\nこんにちは！", "こんにちは！\r\n───", " …… \n  本文〈よみ〉。 ")) {
            val source = input(text)
            val request = StoryTranslationContentPolicy.evaluate(source).prepare(source)
            assertEquals(text, request.dialogue)
            assertEquals(source.name, request.name)
        }
    }

    @Test
    fun `all symbol-only choices are omitted without removing any source slots`() {
        val source = input(null, listOf("……", "『───』", "！？", "☆", "……\n……", "？？？"))
        val decision = StoryTranslationContentPolicy.evaluate(source)
        assertFalse(decision.hasWork)
        assertEquals(List(6) { "" }, decision.prepare(source).choices)
        assertEquals("『───』", source.choices[1])
    }

    @Test
    fun `every mixed choice arrangement up to six buttons retains its exact original indices`() {
        for (count in 1..6) {
            for (mask in 0 until (1 shl count)) {
                val choices = List(count) { index ->
                    if (mask and (1 shl index) != 0) "選択肢$index〈よみ$index〉" else "……"
                }
                val source = input("！？", choices, voiceHint = true)
                val decision = StoryTranslationContentPolicy.evaluate(source)
                val request = decision.prepare(source)

                assertEquals(mask != 0, decision.hasWork, "count=$count mask=$mask")
                assertEquals(count, request.choices.size)
                assertNull(request.name)
                assertNull(request.dialogue)
                assertFalse(request.requestVoiceHint)
                choices.forEachIndexed { index, original ->
                    val expected = mask and (1 shl index) != 0
                    assertEquals(expected, decision.shouldTranslateChoice(index))
                    assertEquals(if (expected) original else "", request.choices[index])
                }
                assertEquals(choices, source.choices)
            }
        }
    }

    @Test
    fun `meaningful dialogue retains its speaker and voice hint when every choice is skipped`() {
        val source = input("おはよう。", listOf("……", "───"), voiceHint = true)
        val decision = StoryTranslationContentPolicy.evaluate(source)
        val request = decision.prepare(source)
        assertTrue(decision.hasWork)
        assertEquals(source.name, request.name)
        assertEquals(source.dialogue, request.dialogue)
        assertTrue(request.requestVoiceHint)
        assertEquals(listOf("", ""), request.choices)
    }

    @Test
    fun `masked speaker name is preserved when the dialogue has words`() {
        val source = input("こんにちは。", name = "？？？")
        assertEquals(source, StoryTranslationContentPolicy.evaluate(source).prepare(source))
    }

    @Test
    fun `empty payload or speaker alone never creates translation work`() {
        for (dialogue in listOf(null, "", " \r\n\t ")) {
            val source = input(dialogue, listOf("", " "))
            val decision = StoryTranslationContentPolicy.evaluate(source)
            assertFalse(decision.hasWork)
            assertNull(decision.prepare(source).name)
        }
    }

    @Test
    fun `request filtering composes with ruby settings without changing original text or previous scenes`() {
        val context = SceneDialogueContext(
            sourceSpeakerName = "マシュ", translatedSpeakerName = "Mash",
            sourceDialogue = "星見台〈カルデア〉へ。", translatedDialogue = "To the observatory〈Chaldea〉.",
            targetLocale = "en", dialogueSourceKey = "previous-source"
        )
        val source = input("……", listOf("！？", "星見台〈カルデア〉へ行く", "……"))
            .copy(previousDialogueContexts = listOf(context))
        val selected = StoryTranslationContentPolicy.evaluate(source).prepare(source)

        assertSame(context, selected.previousDialogueContexts.single())
        assertEquals(listOf("", "星見台〈カルデア〉へ行く", ""), TranslationRubyPolicy.prepare(selected, true).choices)
        assertEquals(listOf("", "星見台へ行く", ""), TranslationRubyPolicy.prepare(selected, false).choices)
        assertEquals(listOf("！？", "星見台〈カルデア〉へ行く", "……"), source.choices)
        assertEquals("星見台〈カルデア〉へ。", context.sourceDialogue)
    }

    @Test
    fun `translated result or equality to source does not participate in eligibility`() {
        for (text in listOf("Chaldea", "マシュ", "一", "123")) {
            assertTrue(StoryTranslationContentPolicy.evaluate(input(text)).hasWork, text)
        }
    }

    private fun input(
        dialogue: String?,
        choices: List<String> = emptyList(),
        name: String? = "マシュ",
        voiceHint: Boolean = false
    ) = SceneTranslateInput(name, dialogue, choices, requestVoiceHint = voiceHint)
}
