package com.lucasmunoz.mtg.ar;

import android.content.Context;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keyword definitions for the AR screen, read from the same keywords.json the web UI uses — it
 * ships inside the bundled web assets (assets/public/keywords.json after `cap sync`), so there
 * is exactly one glossary to maintain.
 */
final class KeywordGlossary {

    private static final String TAG = "KeywordGlossary";
    private static final String ASSET_PATH = "public/keywords.json";

    private final Map<String, String> definitionsByName = new HashMap<>();

    KeywordGlossary(Context context) {
        try (InputStream stream = context.getAssets().open(ASSET_PATH);
                Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())
                        .useDelimiter("\\A")) {
            load(scanner.hasNext() ? scanner.next() : "[]");
        } catch (IOException | JSONException e) {
            // The screen still works without definitions; taps just say the glossary is missing.
            Log.w(TAG, "Could not load the keyword glossary from assets.", e);
        }
    }

    /** Package-visible for unit tests, which feed JSON directly instead of an Android asset. */
    KeywordGlossary(String json) throws JSONException {
        load(json);
    }

    private void load(String json) throws JSONException {
        JSONArray entries = new JSONArray(json);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String name = entry.optString("name", "");
            String definition = entry.optString("definition", "");
            if (!name.isEmpty() && !definition.isEmpty()) {
                definitionsByName.put(name.toLowerCase(Locale.ROOT), definition);
            }
        }
    }

    /** The definition for a keyword, or null when the glossary does not cover it. */
    String lookup(String keyword) {
        return definitionsByName.get(keyword.trim().toLowerCase(Locale.ROOT));
    }

    int size() {
        return definitionsByName.size();
    }
}
