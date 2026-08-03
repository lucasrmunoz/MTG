package com.lucasmunoz.mtg.ar;

import android.content.Intent;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

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

        getActivity().startActivity(intent);
        call.resolve();
    }

    /** Opens the AR screen with no card: the camera identifies one by reading its title. */
    @PluginMethod
    public void scan(PluginCall call) {
        getActivity().startActivity(new Intent(getActivity(), ArCardActivity.class));
        call.resolve();
    }
}
