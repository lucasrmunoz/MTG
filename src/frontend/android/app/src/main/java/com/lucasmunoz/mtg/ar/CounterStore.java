package com.lucasmunoz.mtg.ar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists counters per printing, so they are still on a card the next time it is scanned —
 * hours or days later, on any table.
 *
 * Exactly one file ever exists ({@code ar-counters.json} in the app's private files dir),
 * rewritten whole through a temp-file rename on every change — the same replace-in-place policy
 * as the Card Kingdom price cache. A corrupt file is discarded rather than crashing the AR view.
 */
public final class CounterStore {

    private static final String FILE_NAME = "ar-counters.json";

    private final File file;
    private final File tempFile;
    private final Map<String, CardCounters> cards = new HashMap<>();

    public CounterStore(File directory) {
        this.file = new File(directory, FILE_NAME);
        this.tempFile = new File(directory, FILE_NAME + ".tmp");
        load();
    }

    /** The counters for one printing, created empty on first use. */
    public synchronized CardCounters get(String printingId) {
        CardCounters counters = cards.get(printingId);
        if (counters == null) {
            counters = new CardCounters();
            cards.put(printingId, counters);
        }
        return counters;
    }

    /**
     * Writes the current state to disk. Entries with nothing on them are dropped, so the file
     * only ever holds cards that actually carry counters.
     */
    public synchronized void save() throws IOException {
        JSONObject cardsJson = new JSONObject();
        try {
            for (Map.Entry<String, CardCounters> entry : cards.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    cardsJson.put(entry.getKey(), entry.getValue().toJson());
                }
            }

            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("cards", cardsJson);

            Files.write(tempFile.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Could not serialise the counter store.", e);
        }

        // Rename over the previous file so a crash mid-write can never leave a half-written
        // store as the only copy. renameTo does not replace on every filesystem, hence the
        // delete first; the temp file survives either way until the next successful save.
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not replace " + file);
        }
        if (!tempFile.renameTo(file)) {
            throw new IOException("Could not move " + tempFile + " into place.");
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }

        try {
            String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(contents);
            JSONObject cardsJson = root.optJSONObject("cards");
            if (cardsJson == null) {
                return;
            }
            for (java.util.Iterator<String> it = cardsJson.keys(); it.hasNext(); ) {
                String id = it.next();
                cards.put(id, CardCounters.fromJson(cardsJson.getJSONObject(id)));
            }
        } catch (IOException | JSONException e) {
            // A corrupt store is abandoned; counters restart empty rather than the view crashing.
            // The next save rewrites the file whole.
            android.util.Log.w("CounterStore", "Discarding unreadable counter store.", e);
            cards.clear();
        }
    }
}
