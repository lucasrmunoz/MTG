package com.lucasmunoz.mtg.ar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The players of one Commander game while the AR screen has them. Parsed from the plugin's JSON
 * array and serialised back to the same shape for the activity result — the web layer merges only
 * life and casts from it, so this never needs to persist anything.
 */
public final class GameSession {

    private final List<GamePlayer> players;

    private GameSession(List<GamePlayer> players) {
        this.players = Collections.unmodifiableList(players);
    }

    /** Strict: an empty or malformed players array is our own web code misbehaving. */
    public static GameSession fromJson(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        if (array.length() == 0) {
            throw new JSONException("The game payload has no players.");
        }
        List<GamePlayer> players = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            players.add(GamePlayer.fromJson(array.getJSONObject(i)));
        }
        return new GameSession(players);
    }

    public String toJsonString() throws JSONException {
        JSONArray array = new JSONArray();
        for (GamePlayer player : players) {
            array.put(player.toJson());
        }
        return array.toString();
    }

    public List<GamePlayer> players() {
        return players;
    }

    public GamePlayer playerById(int id) {
        for (GamePlayer player : players) {
            if (player.id == id) {
                return player;
            }
        }
        return null;
    }
}
