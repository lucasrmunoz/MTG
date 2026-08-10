package com.lucasmunoz.mtg.ar;

import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONException;

/**
 * Web-to-native entry point for the AR screen. The web UI passes the looked-up card and its
 * printings; everything after that — camera, tracking, counters — is native.
 */
@CapacitorPlugin(name = "CardAr")
public class CardArPlugin extends Plugin {

    @PluginMethod
    public void open(PluginCall call) {
        String cardId = call.getString("cardId");
        String cardName = call.getString("cardName");
        String imageUrl = call.getString("imageUrl");
        if (cardId == null || cardName == null || imageUrl == null) {
            call.reject("cardId, cardName and imageUrl are required.");
            return;
        }

        Intent intent = new Intent(getActivity(), ArCardActivity.class);
        intent.putExtra(ArCardActivity.EXTRA_CARD_ID, cardId);
        intent.putExtra(ArCardActivity.EXTRA_CARD_NAME, cardName);
        intent.putExtra(ArCardActivity.EXTRA_IMAGE_URL, imageUrl);

        JSArray printings = call.getArray("printings");
        if (printings != null) {
            intent.putExtra(ArCardActivity.EXTRA_PRINTINGS, printings.toString());
        }

        JSArray keywords = call.getArray("keywords");
        if (keywords != null) {
            intent.putExtra(ArCardActivity.EXTRA_KEYWORDS, keywords.toString());
        }

        getActivity().startActivity(intent);
        call.resolve();
    }

    /**
     * Opens the AR game session: each player's recognised commander gets a badge with its
     * owner's name, life and tax, adjustable in place. Resolves with the updated players when
     * the screen closes. Commanders are optional: a player without one keeps an adjustable
     * life token, and any card scanned at the table can be bound as their commander in AR.
     */
    @PluginMethod
    public void openGame(PluginCall call) {
        JSArray players = call.getArray("players");
        if (players == null || players.length() == 0 || players.length() > 6) {
            call.reject("players (1-6) is required.");
            return;
        }

        Intent intent = new Intent(getActivity(), ArCardActivity.class);
        intent.putExtra(ArCardActivity.EXTRA_GAME_PLAYERS, players.toString());
        startActivityForResult(call, intent, "gameResult");
    }

    /**
     * The game screen publishes its latest state via setResult on every change, so whatever
     * closes it — the ✕ button, back, even the system killing it — this returns the freshest
     * values it produced. A cancelled launch (camera denied, no ARCore) has no result extra and
     * resolves with the players exactly as they were sent: cancelling is not an error.
     */
    @ActivityCallback
    private void gameResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        Intent data = result.getData();
        String json = data == null
                ? null
                : data.getStringExtra(ArCardActivity.EXTRA_GAME_RESULT);
        try {
            JSObject ret = new JSObject();
            ret.put("players", json != null ? new JSArray(json) : call.getArray("players"));
            call.resolve(ret);
        } catch (JSONException e) {
            call.reject("The AR game returned a malformed result.", e);
        }
    }
}
