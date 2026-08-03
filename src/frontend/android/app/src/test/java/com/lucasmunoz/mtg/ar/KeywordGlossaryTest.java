package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class KeywordGlossaryTest {

    private static final String JSON =
            "[{\"name\":\"Flying\",\"definition\":\"Can only be blocked by flyers and reach.\"},"
                    + "{\"name\":\"First strike\",\"definition\":\"Hits first.\"},"
                    + "{\"name\":\"\",\"definition\":\"nameless\"},"
                    + "{\"name\":\"Empty\",\"definition\":\"\"}]";

    @Test
    public void looksUpCaseInsensitively() throws Exception {
        KeywordGlossary glossary = new KeywordGlossary(JSON);
        assertEquals("Hits first.", glossary.lookup("first strike"));
        assertEquals("Hits first.", glossary.lookup("FIRST STRIKE"));
        assertEquals("Can only be blocked by flyers and reach.", glossary.lookup(" Flying "));
    }

    @Test
    public void unknownAndMalformedEntriesReturnNull() throws Exception {
        KeywordGlossary glossary = new KeywordGlossary(JSON);
        assertNull(glossary.lookup("Banding"));
        assertNull(glossary.lookup("Empty"));
        assertEquals(2, glossary.size());
    }
}
