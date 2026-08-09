package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CardNameCatalogTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private CardNameCatalog catalogOf(String... names) {
        CardNameCatalog catalog = new CardNameCatalog(new File(folder.getRoot(), "names.json"));
        catalog.index(Arrays.asList(names));
        return catalog;
    }

    @Test
    public void unloadedCatalogMatchesNothing() {
        CardNameCatalog catalog = new CardNameCatalog(new File(folder.getRoot(), "names.json"));
        assertNull(catalog.bestMatch("Lightning Bolt"));
    }

    @Test
    public void exactNameMatches() {
        CardNameCatalog catalog = catalogOf("Lightning Bolt", "Llanowar Elves");
        assertEquals("Lightning Bolt", catalog.bestMatch("Lightning Bolt"));
    }

    @Test
    public void matchingForgivesCaseDiacriticsAndApostrophes() {
        CardNameCatalog catalog = catalogOf("Lórien Revealed", "Urza's Bauble");
        assertEquals("Lórien Revealed", catalog.bestMatch("LORIEN REVEALED"));
        assertEquals("Urza's Bauble", catalog.bestMatch("Urzas Bauble"));
    }

    @Test
    public void closeMisreadStillMatches() {
        CardNameCatalog catalog = catalogOf("Lightning Bolt", "Llanowar Elves");
        // OCR swapped a letter and lost one: distance 2 on a long name.
        assertEquals("Lightning Bolt", catalog.bestMatch("Lightnimg Bol"));
    }

    @Test
    public void shortNamesGetOnlyOneEdit() {
        CardNameCatalog catalog = catalogOf("Duress");
        assertEquals("Duress", catalog.bestMatch("Duresz"));
        assertNull(catalog.bestMatch("Durezz"));
    }

    @Test
    public void distantLinesMatchNothing() {
        CardNameCatalog catalog = catalogOf("Lightning Bolt");
        assertNull(catalog.bestMatch("Whenever a creature dies"));
        assertNull(catalog.bestMatch("ab"));
        assertNull(catalog.bestMatch(null));
    }

    @Test
    public void uniqueCloseNameWinsOverDistantSiblings() {
        CardNameCatalog catalog = catalogOf("Weldfast Monitor", "Weldfast Wingsmith");
        assertEquals("Weldfast Monitor", catalog.bestMatch("Weldfast Monitos"));
    }

    @Test
    public void tieBetweenEquallyCloseNamesIsRejected() {
        CardNameCatalog catalog = catalogOf("Abandon Hope", "Abandon Hopes");
        // "Abandon Hope" is exact for one — exact wins outright, no ambiguity.
        assertEquals("Abandon Hope", catalog.bestMatch("Abandon Hope"));
        // Distance 1 from both entries: ambiguous, match nothing.
        assertNull(catalog.bestMatch("Abandon Hopez"));
    }

    @Test
    public void faceNameMatchesItsFullCard() {
        CardNameCatalog catalog = catalogOf("Delver of Secrets // Insectile Aberration");
        assertEquals("Delver of Secrets // Insectile Aberration",
                catalog.bestMatch("Delver of Secrets"));
        assertEquals("Delver of Secrets // Insectile Aberration",
                catalog.bestMatch("Insectile Aberration"));
    }

    @Test
    public void faceNameNeverShadowsARealCard() {
        CardNameCatalog catalog = catalogOf("Fire // Ice", "Fire Elemental");
        assertEquals("Fire // Ice", catalog.bestMatch("Fire"));
        CardNameCatalog reversed = catalogOf("Fire", "Fire // Ice");
        assertEquals("Fire", reversed.bestMatch("Fire"));
    }

    @Test
    public void parsesCatalogJson() throws Exception {
        assertEquals(Arrays.asList("Lightning Bolt", "Opt"),
                CardNameCatalog.parseNames(
                        "{\"object\":\"catalog\",\"data\":[\"Lightning Bolt\",\"Opt\"]}"));
    }

    @Test(expected = org.json.JSONException.class)
    public void rejectsCatalogJsonWithoutData() throws Exception {
        CardNameCatalog.parseNames("{\"object\":\"catalog\"}");
    }

    @Test
    public void normalizeCollapsesPunctuationAndSpacing() {
        assertEquals("ach hans run", CardNameCatalog.normalize("\"Ach! Hans, Run!\""));
        assertEquals("lorien revealed", CardNameCatalog.normalize("Lórien  Revealed"));
        assertEquals("urzas bauble", CardNameCatalog.normalize("Urza's Bauble"));
        assertEquals("", CardNameCatalog.normalize(null));
    }

    @Test
    public void boundedEditDistanceStopsEarly() {
        assertEquals(0, CardNameCatalog.boundedEditDistance("bolt", "bolt", 2));
        assertEquals(1, CardNameCatalog.boundedEditDistance("bolt", "bolts", 2));
        assertEquals(2, CardNameCatalog.boundedEditDistance("bolt", "bots", 2));
        assertTrue(CardNameCatalog.boundedEditDistance("lightning", "llanowar", 2) > 2);
    }
}
