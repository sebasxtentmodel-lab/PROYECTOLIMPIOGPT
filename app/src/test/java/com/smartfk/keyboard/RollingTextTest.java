package com.smartfk.keyboard;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RollingTextTest {
    @Test public void firstSentenceIsComplete() {
        assertEquals("Hola, ¿cómo estás?", RollingText.newTail("", " Hola, ¿cómo estás? "));
    }
    @Test public void overlapAppendsOnlyNewWords() {
        assertEquals("contigo mañana.", RollingText.newTail(
            "Hola, me gustaría hablar contigo", "me gustaría hablar contigo mañana."));
    }
    @Test public void longRepeatedSentenceIsIgnored() {
        assertEquals("", RollingText.newTail(
            "Hola buenos días señor", "hola buenos días señor"));
    }
    @Test public void shortIntentionalRepetitionIsPreserved() {
        assertEquals("sí", RollingText.newTail("sí", "sí"));
        assertEquals("evet evet", RollingText.newTail("evet", "evet evet"));
    }
    @Test public void unrelatedPhraseIsPreserved() {
        assertEquals("Hasta mañana", RollingText.newTail("Hola, buenos días", "Hasta mañana"));
    }
    @Test public void blankOrNullDoesNothing() {
        assertEquals("", RollingText.newTail("Hola", null));
        assertEquals("", RollingText.newTail("Hola", " "));
    }
}
