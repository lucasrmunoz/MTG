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
        // Absent type line, set name and oracle text parse as empty, never null.
        assertEquals("", card.typeLine);
        assertEquals("", card.setName);
        assertEquals("", card.oracleText);
        assertEquals(0, card.tokenParts.size());
    }

    @Test
    public void parsesOracleTextAndTokenParts() throws Exception {
        // Shaped like Army of the Damned: all_parts links the card itself and its token.
        JSONObject json = new JSONObject(
                "{\"id\":\"army\",\"name\":\"Army of the Damned\","
                        + "\"oracle_text\":\"Create thirteen tapped 2/2 black Zombie creature "
                        + "tokens.\","
                        + "\"all_parts\":["
                        + "{\"component\":\"combo_piece\",\"id\":\"army\","
                        + "\"name\":\"Army of the Damned\"},"
                        + "{\"component\":\"token\",\"id\":\"zombie-token\","
                        + "\"name\":\"Zombie\"}],"
                        + "\"image_uris\":{\"normal\":\"https://img/army.jpg\"}}");
        ScryfallLookup.CardSummary card = ScryfallLookup.parseCard(json);
        assertEquals("Create thirteen tapped 2/2 black Zombie creature tokens.",
                card.oracleText);
        assertEquals(1, card.tokenParts.size());
        assertEquals("zombie-token", card.tokenParts.get(0).id);
        assertEquals("Zombie", card.tokenParts.get(0).name);
    }

    @Test
    public void joinsFaceOracleTexts() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"dfc\",\"name\":\"Front // Back\","
                        + "\"card_faces\":[{\"name\":\"Front\","
                        + "\"oracle_text\":\"Create a Treasure token.\","
                        + "\"image_uris\":{\"normal\":\"https://img/front.jpg\"}},"
                        + "{\"name\":\"Back\",\"oracle_text\":\"Flying\"}]}");
        ScryfallLookup.CardSummary card = ScryfallLookup.parseCard(json);
        assertEquals("Create a Treasure token.\nFlying", card.oracleText);
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
