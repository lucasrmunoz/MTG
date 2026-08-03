package com.lucasmunoz.mtg.ar;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One Commander player as the AR game session sees them. The web layer owns the game; this is a
 * session-scoped working copy whose only mutable fields are the two the AR screen may change —
 * life and command-zone casts. The same JSON shape crosses the bridge in both directions.
 */
public final class GamePlayer {

    public final int id;
    public final String name;
    /** Unclamped: cards like Angel's Grace make negative totals a real game state. */
    public int life;
    public int commanderCasts;

    /** The chosen commander printing, or all-null when the player has none to track. */
    public final String cardId;
    public final String cardName;
    public final String cardImageUrl;
    /** Art-only crop for the life token; null when Scryfall has none (the scan stands in). */
    public final String cardArtCropUrl;

    GamePlayer(int id, String name, int life, int commanderCasts,
            String cardId, String cardName, String cardImageUrl, String cardArtCropUrl) {
        this.id = id;
        this.name = name;
        this.life = life;
        this.commanderCasts = Math.max(0, commanderCasts);
        this.cardId = cardId;
        this.cardName = cardName;
        this.cardImageUrl = cardImageUrl;
        this.cardArtCropUrl = cardArtCropUrl;
    }

    /** True when there is a commander scan the camera can look for. */
    public boolean hasCard() {
        return cardId != null;
    }

    public void adjustLife(int delta) {
        life += delta;
    }

    public void adjustCasts(int delta) {
        commanderCasts = Math.max(0, commanderCasts + delta);
    }

    /** The extra generic mana the commander costs right now; same rule as card counters. */
    public int commanderTax() {
        return CardCounters.taxForCasts(commanderCasts);
    }

    /**
     * Strict on required fields — a malformed payload is a bug in our own web code, so it fails
     * fast rather than limping along with defaults. A missing or null card is the one legal
     * absence: it means "nothing to track", not an error.
     */
    public static GamePlayer fromJson(JSONObject json) throws JSONException {
        int id = json.getInt("id");
        String name = json.getString("name");
        int life = json.getInt("life");
        int casts = json.getInt("commanderCasts");

        JSONObject card = json.isNull("card") ? null : json.optJSONObject("card");
        if (card == null) {
            return new GamePlayer(id, name, life, casts, null, null, null, null);
        }
        // artCropUrl is optional and nullable — older payloads simply have no token art.
        String artCropUrl = card.isNull("artCropUrl") ? null : card.getString("artCropUrl");
        return new GamePlayer(id, name, life, casts,
                card.getString("id"), card.getString("name"), card.getString("imageUrl"),
                artCropUrl);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("life", life);
        json.put("commanderCasts", commanderCasts);
        if (cardId == null) {
            json.put("card", JSONObject.NULL);
        } else {
            JSONObject card = new JSONObject();
            card.put("id", cardId);
            card.put("name", cardName);
            card.put("imageUrl", cardImageUrl);
            card.put("artCropUrl", cardArtCropUrl == null ? JSONObject.NULL : cardArtCropUrl);
            json.put("card", card);
        }
        return json;
    }
}
