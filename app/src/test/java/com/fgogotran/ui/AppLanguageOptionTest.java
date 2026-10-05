package com.fgogotran.ui;

import com.fgogotran.Screen;
import com.fgogotran.ui.component.LanguagePickerDialogKt;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AppLanguageOptionTest {
    @Test
    public void explicitLanguageNamesStayInTheirOwnScripts() {
        for (String followSystem : new String[] {"Follow system", "跟隨系統", "跟随系统"}) {
            assertEquals("English", LanguagePickerDialogKt.appLanguageLabel("en", followSystem));
            assertEquals("繁體中文", LanguagePickerDialogKt.appLanguageLabel("zh-Hant", followSystem));
            assertEquals("简体中文", LanguagePickerDialogKt.appLanguageLabel("zh-Hans", followSystem));
        }
    }

    @Test
    public void systemSelectionShowsThePreferenceInsteadOfAnExplicitLanguage() {
        assertEquals("Follow system", LanguagePickerDialogKt.appLanguageLabel("system", "Follow system"));
        assertEquals("跟隨系統", LanguagePickerDialogKt.appLanguageLabel("system", "跟隨系統"));
        assertEquals("跟随系统", LanguagePickerDialogKt.appLanguageLabel("system", "跟随系统"));
    }

    @Test
    public void unsupportedPreferencesKeepTheExistingSystemFallback() {
        assertEquals("Follow system", LanguagePickerDialogKt.appLanguageLabel("ja", "Follow system"));
        assertEquals("Follow system", LanguagePickerDialogKt.appLanguageLabel("", "Follow system"));
    }

    @Test
    public void navigationScreenCanRoundTripAsSavedSerializableState() throws Exception {
        for (Screen screen : Screen.values()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(screen);
            }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertSame(screen, input.readObject());
            }
        }
    }
}
