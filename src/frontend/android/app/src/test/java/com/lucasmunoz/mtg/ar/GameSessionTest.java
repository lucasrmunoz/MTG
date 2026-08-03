package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONException;
import org.junit.Test;

/** The AR game payload: strict parsing, lossless round-trips, and the two mutable fields. */
public class GameSessionTest {

    private static String player(int id, String name, int life, int casts, String card) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"life\":" + life
                + ",\"commanderCasts\":" + casts + ",\"card\":" + card + "}";
    }

    private static String card(String id, String name, String imageUrl) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"imageUrl\":\"" + imageUrl + "\"}";
    }

    @Test
    public void roundTripKeepsEveryPlayerInOrder() throws JSONException {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 6; i++) {
            if (i > 1) {
                json.append(",");
            }
            json.append(player(i, "Player " + i, 40 - i, i,
                    card("id-" + i, "Commander " + i, "https://img/" + i)));
        }
        json.append("]");

        GameSession session = GameSession.fromJson(json.toString());
        GameSession reparsed = GameSession.fromJson(session.toJsonString());

        List<GamePlayer> players = reparsed.players();
        assertEquals(6, players.size());
        for (int i = 1; i <= 6; i++) {
            GamePlayer player = players.get(i - 1);
            assertEquals(i, player.id);
            assertEquals("Player " + i, player.name);
            assertEquals(40 - i, player.life);
            assertEquals(i, player.commanderCasts);
            assertEquals("id-" + i, player.cardId);
            assertEquals("Commander " + i, player.cardName);
            assertEquals("https://img/" + i, player.cardImageUrl);
        }
    }

    @Test
    public void nullCardRoundTripsAsNull() throws JSONException {
        GameSession session = GameSession.fromJson(
                "[" + player(1, "Lucas", 40, 0, "null") + "]");
        GamePlayer player = session.players().get(0);
        assertNull(player.cardId);
        assertEquals(false, player.hasCard());

        GamePlayer reparsed = GameSession.fromJson(session.toJsonString()).players().get(0);
        assertNull(reparsed.cardId);
        assertNull(reparsed.cardName);
        assertNull(reparsed.cardImageUrl);
    }

    @Test
    public void missingRequiredFieldThrows() {
        assertThrows(JSONException.class, () -> GameSession.fromJson(
                "[{\"id\":1,\"name\":\"Lucas\",\"commanderCasts\":0,\"card\":null}]"));
    }

    @Test
    public void malformedJsonThrows() {
        assertThrows(JSONException.class, () -> GameSession.fromJson("not json"));
    }

    @Test
    public void emptyPlayerListThrows() {
        assertThrows(JSONException.class, () -> GameSession.fromJson("[]"));
    }

    @Test
    public void negativeCastsParseToZero() throws JSONException {
        GameSession session = GameSession.fromJson(
                "[" + player(1, "Lucas", 40, -3, "null") + "]");
        assertEquals(0, session.players().get(0).commanderCasts);
    }

    @Test
    public void castsClampAtZeroButLifeGoesNegative() throws JSONException {
        GamePlayer player = GameSession.fromJson(
                "[" + player(1, "Lucas", 1, 0, "null") + "]").players().get(0);

        player.adjustCasts(-1);
        assertEquals(0, player.commanderCasts);

        // Life is deliberately unclamped: negative totals are a real game state.
        player.adjustLife(-3);
        assertEquals(-2, player.life);
    }

    @Test
    public void taxIsTwoPerCast() throws JSONException {
        GamePlayer player = GameSession.fromJson(
                "[" + player(1, "Lucas", 40, 0, "null") + "]").players().get(0);
        assertEquals(0, player.commanderTax());
        player.adjustCasts(3);
        assertEquals(6, player.commanderTax());
    }

    @Test
    public void playerByIdFindsAndMisses() throws JSONException {
        GameSession session = GameSession.fromJson(
                "[" + player(1, "A", 40, 0, "null") + "," + player(2, "B", 40, 0, "null") + "]");
        assertNotNull(session.playerById(2));
        assertEquals("B", session.playerById(2).name);
        assertNull(session.playerById(9));
    }

    @Test
    public void mutationsSurviveSerialisation() throws JSONException {
        GameSession session = GameSession.fromJson(
                "[" + player(1, "Lucas", 40, 0, card("c1", "Atraxa", "https://img/1")) + "]");
        GamePlayer player = session.players().get(0);
        player.adjustLife(-7);
        player.adjustCasts(2);

        GamePlayer reparsed = GameSession.fromJson(session.toJsonString()).players().get(0);
        assertEquals(33, reparsed.life);
        assertEquals(2, reparsed.commanderCasts);
        assertTrue(reparsed.hasCard());
    }
}
