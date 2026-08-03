package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ScryfallLookupTest {

    @Test
    public void parsesTopLevelImage() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"abc\",\"name\":\"Lightning Bolt\","
                        + "\"image_uris\":{\"normal\":\"https://img/bolt.jpg\"}}");
        ScryfallLookup.CardSummary card = ScryfallLookup.parseCard(json);
        assertEquals("abc", card.id);
        assertEquals("Lightning Bolt", card.name);
        assertEquals("https://img/bolt.jpg", card.imageUrl);
        assertEquals(0, card.keywords.size());
        // Absent type line and set name parse as empty, never null.
        assertEquals("", card.typeLine);
        assertEquals("", card.setName);
    }

    @Test
    public void parsesTypeLineAndSetName() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"abc\",\"name\":\"Lightning Bolt\","
                        + "\"type_line\":\"Instant\",\"set_name\":\"Magic 2010\","
                        + "\"image_uris\":{\"normal\":\"https://img/bolt.jpg\"}}");
        ScryfallLookup.CardSummary card = ScryfallLookup.parseCard(json);
        assertEquals("Instant", card.typeLine);
        assertEquals("Magic 2010", card.setName);
    }

    @Test
    public void parsesKeywords() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"abc\",\"name\":\"Serra Angel\","
                        + "\"keywords\":[\"Flying\",\"Vigilance\"],"
                        + "\"image_uris\":{\"normal\":\"https://img/serra.jpg\"}}");
        ScryfallLookup.CardSummary card = ScryfallLookup.parseCard(json);
        assertEquals(2, card.keywords.size());
        assertEquals("Flying", card.keywords.get(0));
        assertEquals("Vigilance", card.keywords.get(1));
    }

    @Test
    public void fallsBackToFirstFaceWithImage() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"dfc\",\"name\":\"Delver of Secrets // Insectile Aberration\","
                        + "\"card_faces\":[{\"name\":\"Delver of Secrets\","
                        + "\"image_uris\":{\"normal\":\"https://img/delver.jpg\"}},"
                        + "{\"name\":\"Insectile Aberration\"}]}");
        ScryfallLookup.CardSummary card = ScryfallLookup.parseCard(json);
        assertEquals("https://img/delver.jpg", card.imageUrl);
    }

    @Test
    public void missingIdOrNameIsNull() throws Exception {
        assertNull(ScryfallLookup.parseCard(new JSONObject("{\"name\":\"x\"}")));
        assertNull(ScryfallLookup.parseCard(new JSONObject("{\"id\":\"x\"}")));
    }

    @Test
    public void artListSkipsImagelessPrintings() throws Exception {
        JSONObject json = new JSONObject(
                "{\"data\":[{\"id\":\"a\",\"name\":\"Bolt\","
                        + "\"image_uris\":{\"normal\":\"https://img/a.jpg\"}},"
                        + "{\"id\":\"b\",\"name\":\"Bolt\"},"
                        + "{\"id\":\"c\",\"name\":\"Bolt\","
                        + "\"image_uris\":{\"normal\":\"https://img/c.jpg\"}}]}");
        List<ScryfallLookup.CardSummary> versions = ScryfallLookup.parseArtList(json);
        assertEquals(2, versions.size());
        assertEquals("a", versions.get(0).id);
        assertEquals("c", versions.get(1).id);
    }

    @Test
    public void emptyOrMissingDataIsEmptyList() throws Exception {
        assertEquals(0, ScryfallLookup.parseArtList(new JSONObject("{}")).size());
        assertEquals(0, ScryfallLookup.parseArtList(new JSONObject("{\"data\":[]}")).size());
    }
}
