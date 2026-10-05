package com.fgogotran.ui.theme;

import com.fgogotran.data.AppThemeMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AppThemeModeTest {
    @Test
    public void missingOrUnknownPreferenceFollowsSystem() {
        assertSame(AppThemeMode.SYSTEM, AppThemeMode.Companion.fromPreference(null));
        assertSame(AppThemeMode.SYSTEM, AppThemeMode.Companion.fromPreference(""));
        assertSame(AppThemeMode.SYSTEM, AppThemeMode.Companion.fromPreference("unknown"));
    }

    @Test
    public void everyPreferenceRoundTrips() {
        for (AppThemeMode mode : AppThemeMode.values()) {
            assertSame(mode, AppThemeMode.Companion.fromPreference(mode.getPreferenceValue()));
        }
    }

    @Test
    public void systemModeTracksBothPhoneThemes() {
        assertFalse(AppThemeMode.SYSTEM.isDark(false));
        assertTrue(AppThemeMode.SYSTEM.isDark(true));
    }

    @Test
    public void lightModeDoesNotFollowPhoneChanges() {
        assertFalse(AppThemeMode.LIGHT.isDark(false));
        assertFalse(AppThemeMode.LIGHT.isDark(true));
    }

    @Test
    public void darkModeDoesNotFollowPhoneChanges() {
        assertTrue(AppThemeMode.DARK.isDark(false));
        assertTrue(AppThemeMode.DARK.isDark(true));
    }
}
