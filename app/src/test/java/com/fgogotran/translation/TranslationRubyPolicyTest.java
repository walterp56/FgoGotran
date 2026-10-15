package com.fgogotran.translation;

import com.fgogotran.data.SettingsRepository;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TranslationRubyPolicyTest {
    private SceneTranslateInput input(String dialogue, List<String> choices) {
        return new SceneTranslateInput("ジャック", dialogue, choices, false, List.of());
    }

    private SceneTranslateInput prepare(SceneTranslateInput source, boolean includeRuby) {
        return TranslationRubyPolicy.INSTANCE.prepare(source, includeRuby);
    }

    @Test
    public void defaultIncludesRuby() {
        assertTrue(SettingsRepository.DEFAULT_TRANSLATION_INCLUDE_RUBY);
    }

    @Test
    public void onPassesTheFullRecognisedSourceWithoutChangingItsFormat() {
        SceneTranslateInput source = input(
                "……おかあさん〈マスター〉───\n『南瓜狼〈パンプキンウルフ〉』！",
                List.of("農人〈ファーマー〉？", "……"));
        assertSame(source, prepare(source, true));
    }

    @Test
    public void offRemovesOnlyRubyAndRetainsBasePunctuationAndChoiceOrder() {
        SceneTranslateInput source = input(
                "「……おかあさん〈マスター〉……───」\n『農人〈ファーマー〉』。",
                List.of("……南瓜狼〈パンプキンウルフ〉───", "『農人〈ファーマー〉』？", "……"));
        SceneTranslateInput request = prepare(source, false);

        assertEquals("「……おかあさん……───」\n『農人』。", request.getDialogue());
        assertEquals(List.of("……南瓜狼───", "『農人』？", "……"), request.getChoices());
        assertEquals("ジャック", request.getName());
        assertEquals(source.getRequestVoiceHint(), request.getRequestVoiceHint());
        assertEquals("「……おかあさん〈マスター〉……───」\n『農人〈ファーマー〉』。", source.getDialogue());
        assertEquals("……南瓜狼〈パンプキンウルフ〉───", source.getChoices().get(0));
    }

    @Test
    public void choiceLookupGuardDetectsIncludedRubyButAllowsBaseOnlyWhenOff() {
        SceneTranslateInput source = input(null, List.of(
                "マシュ〈ナレーター〉", "南瓜狼〈パンプキンウルフ〉", "……───", "マシュ《ナレーター》"));
        SceneTranslateInput included = prepare(source, true);
        SceneTranslateInput excluded = prepare(source, false);

        for (int index = 0; index < 2; index++) {
            assertTrue(TextNormalizer.INSTANCE.hasRubyAnnotations(included.getChoices().get(index)));
            assertFalse(TextNormalizer.INSTANCE.hasRubyAnnotations(excluded.getChoices().get(index)));
        }
        assertEquals("マシュ", excluded.getChoices().get(0));
        assertEquals("南瓜狼", excluded.getChoices().get(1));
        assertFalse(TextNormalizer.INSTANCE.hasRubyAnnotations(included.getChoices().get(2)));
        assertFalse(TextNormalizer.INSTANCE.hasRubyAnnotations(included.getChoices().get(3)));
        assertEquals("……───", excluded.getChoices().get(2));
        assertEquals("マシュ《ナレーター》", excluded.getChoices().get(3));
    }

    @Test
    public void offAlsoHandlesLongAndSpacedAnnotations() {
        String longRuby = "カ".repeat(40);
        SceneTranslateInput request = prepare(input(
                "通常空間〈そ　と〉───南瓜狼〈" + longRuby + "〉……", List.of()), false);
        assertEquals("通常空間───南瓜狼……", request.getDialogue());
    }

    @Test
    public void offDoesNotModifyTextWithoutRuby() {
        String text = "　本文  \t です\n……───『！』";
        assertEquals(text, prepare(input(text, List.of(text)), false).getDialogue());
        assertEquals(List.of(text), prepare(input(text, List.of(text)), false).getChoices());
    }

    @Test
    public void offFiltersRequestContextWithoutMutatingHistoryOrSpeakerNames() {
        SceneDialogueContext context = new SceneDialogueContext(
                "名前〈よみ〉", "Name〈reading〉", "農人〈ファーマー〉……",
                "農人〈Farmer〉……", "en", "original-source-key", "農人〈ファーマー〉……");
        SceneTranslateInput source = new SceneTranslateInput(
                "名前〈よみ〉", "おかあさん〈マスター〉", List.of(), true, List.of(context));
        SceneTranslateInput request = prepare(source, false);
        SceneDialogueContext requestContext = request.getPreviousDialogueContexts().get(0);

        assertEquals("農人……", requestContext.getSourceDialogue());
        assertEquals("農人……", requestContext.getTranslatedDialogue());
        assertEquals("農人……", requestContext.getGeneralPreparedSourceDialogue());
        assertEquals(context.getSourceSpeakerName(), requestContext.getSourceSpeakerName());
        assertEquals(context.getTranslatedSpeakerName(), requestContext.getTranslatedSpeakerName());
        assertEquals(context.getTargetLocale(), requestContext.getTargetLocale());
        assertEquals(context.getDialogueSourceKey(), requestContext.getDialogueSourceKey());
        assertEquals(source.getName(), request.getName());
        assertTrue(request.getRequestVoiceHint());
        assertSame(context, source.getPreviousDialogueContexts().get(0));
        assertEquals("農人〈ファーマー〉……", context.getSourceDialogue());
        assertEquals("農人〈Farmer〉……", context.getTranslatedDialogue());
        assertEquals("農人〈ファーマー〉……", context.getGeneralPreparedSourceDialogue());
        assertSame(source, prepare(source, true));
    }

    @Test
    public void laterSwitchesDoNotChangeAnAlreadyPreparedRequest() {
        SceneTranslateInput source = input("おかあさん〈マスター〉", List.of("南瓜狼〈パンプキンウルフ〉"));
        SceneTranslateInput firstRequest = prepare(source, true);
        SceneTranslateInput nextRequest = prepare(source, false);
        SceneTranslateInput lastRequest = prepare(source, true);

        assertEquals("おかあさん〈マスター〉", firstRequest.getDialogue());
        assertEquals("おかあさん", nextRequest.getDialogue());
        assertEquals("おかあさん〈マスター〉", lastRequest.getDialogue());
        assertSame(source, lastRequest);
    }

    @Test
    public void offKeepsNullDialogueAndEmptyChoicesInTheirOriginalPositions() {
        SceneTranslateInput request = prepare(input(null, List.of("", "……", "農人〈ファーマー〉")), false);
        assertEquals(null, request.getDialogue());
        assertEquals(List.of("", "……", "農人"), request.getChoices());
    }

    @Test
    public void uncertainOrAnnotationOnlyTextIsNotSilentlyDeleted() {
        List.of("〈マスター〉", "本文〈未完成……", "……───", "").forEach(text ->
                assertEquals(text, prepare(input(text, List.of()), false).getDialogue()));
    }
}
