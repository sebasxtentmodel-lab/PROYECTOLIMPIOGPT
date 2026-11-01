package com.smartfk.keyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanguageModeTest {
    @Test public void onlyTwoExplicitLanguages() {
        assertEquals("tr", LanguageMode.normalized("tr"));
        assertEquals("es", LanguageMode.normalized("es"));
        assertEquals("auto", LanguageMode.normalized(null));
        assertEquals("auto", LanguageMode.normalized("en"));
    }
    @Test public void turkishSpecialistIsNotUsedForSpanishOrAuto() {
        assertFalse(LanguageMode.useTurkishSpecialist("auto"));
        assertFalse(LanguageMode.useTurkishSpecialist("es"));
        assertTrue(LanguageMode.useTurkishSpecialist("tr"));
    }
}
