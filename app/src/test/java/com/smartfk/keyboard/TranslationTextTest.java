package com.smartfk.keyboard;

import org.junit.Test;
import static org.junit.Assert.*;

/** Locale-correct Spanish/Turkish text normalization for offline NMT. */
public final class TranslationTextTest {
    @Test public void turkishUppercasesDottedI(){
        assertEquals("İstanbul güzel.", OfflineTranslation.prepareText("tr","istanbul güzel"));
    }
    @Test public void keepsAccentsAndTerminalPunctuation(){
        assertEquals("¿Cómo estás?",OfflineTranslation.prepareText("es","¿Cómo estás?"));
        assertEquals("Merhaba.",OfflineTranslation.prepareText("tr","merhaba"));
    }
    @Test public void collapsesWhitespace(){
        assertEquals("Hola buenos días.",OfflineTranslation.prepareText("es","  hola   buenos   días  "));
    }
}
