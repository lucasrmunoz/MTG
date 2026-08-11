package com.lucasmunoz.mtg.ar;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every card name Scryfall knows, cached on disk as one replaced file and matched locally.
 *
 * This is what makes scanning fast: OCR lines resolve to card names on-device in milliseconds
 * instead of one network round trip per candidate line, so the only network cost left is a
 * single exact-name fetch per card that actually joins. Matching ranks, it never guesses: an
 * ambiguous read (two names equally close) matches nothing rather than picking one.
 */
final class CardNameCatalog {

    private static final String TAG = "CardNameCatalog";

    /** A week-old catalog still names ~every card in play; refreshing is a courtesy. */
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /** Normalized queries shorter than this match nothing — too little signal to trust. */
    private static final int MIN_QUERY_LENGTH = 3;

    private final File cacheFile;

    /** Normalized name (full or face) → canonical printed name. Replaced whole, never mutated. */
    private volatile Map<String, String> namesByNormalized = Collections.emptyMap();

    CardNameCatalog(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * Loads the disk cache and refreshes it from Scryfall when missing or stale. Call on a
     * background executor; {@link #bestMatch} works as soon as either source has provided
     * names and returns null before that.
     */
    void ensureLoaded() {
        if (cacheFile.isFile()) {
            try {
                index(parseNames(readWholeFile(cacheFile)));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Unreadable card-name cache; refetching.", e);
            }
        }
        boolean fresh = !namesByNormalized.isEmpty()
                && System.currentTimeMillis() - cacheFile.lastModified() < MAX_AGE_MS;
        if (fresh) {
            return;
        }
        try {
            String json = ScryfallLookup.cardNamesJson();
            // Parse before writing: a bad body must not clobber a good cache.
            index(parseNames(json));
            writeReplacing(json);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Card-name catalog refresh failed; matching uses "
                    + (namesByNormalized.isEmpty() ? "nothing until a later retry" : "the cached copy"), e);
        }
    }

    /** True once names are available; before that every match is null, not an error. */
    boolean isReady() {
        return !namesByNormalized.isEmpty();
    }

    /**
     * The catalog name this line reads exactly (after normalization), or null. Distinguishes
     * a clean title read from {@link #bestMatch}'s edit-tolerant hits, which callers may want
     * to discard when an exact read is present — a type-line fragment like "Land" sits one
     * edit from a real card name, and OCR noise from an upside-down read does the same.
     */
    String exactMatch(String ocrLine) {
        Map<String, String> names = namesByNormalized;
        if (names.isEmpty() || ocrLine == null) {
            return null;
        }
        String query = normalize(ocrLine);
        return query.length() < MIN_QUERY_LENGTH ? null : names.get(query);
    }

    /**
     * The catalog name best matching an OCR line, or null when nothing is close enough or two
     * different names are equally close. Exact normalized hits win outright; otherwise the
     * closest name within a length-scaled edit budget (1 edit for short names, 2 for longer)
     * is returned only when it is the unique best.
     */
    String bestMatch(String ocrLine) {
        Map<String, String> names = namesByNormalized;
        if (names.isEmpty() || ocrLine == null) {
            return null;
        }
        String query = normalize(ocrLine);
        if (query.length() < MIN_QUERY_LENGTH) {
            return null;
        }
        String exact = names.get(query);
        if (exact != null) {
            return exact;
        }

        int maxEdits = query.length() <= 6 ? 1 : 2;
        int bestDistance = maxEdits + 1;
        String best = null;
        boolean tie = false;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String candidate = entry.getKey();
            if (Math.abs(candidate.length() - query.length()) > maxEdits) {
                continue;
            }
            int distance = boundedEditDistance(query, candidate, maxEdits);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
                tie = false;
            } else if (distance == bestDistance && !entry.getValue().equals(best)) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /**
     * Lowercased, diacritics stripped, apostrophes removed (OCR drops them constantly), all
     * other punctuation collapsed to single spaces — so "Lórien Revealed" and "Urza's Bauble"
     * meet their most common misreads at distance zero.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("['’`]", "");
        return lower.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Levenshtein distance, or cutoff + 1 as soon as it can no longer come in under cutoff. */
    static int boundedEditDistance(String a, String b, int cutoff) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > cutoff) {
                return cutoff + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** The catalog response's name list. Package-visible for unit tests. */
    static List<String> parseNames(String json) throws JSONException {
        JSONArray data = new JSONObject(json).optJSONArray("data");
        if (data == null) {
            throw new JSONException("Card-name catalog response has no data array.");
        }
        List<String> names = new ArrayList<>(data.length());
        for (int i = 0; i < data.length(); i++) {
            names.add(data.getString(i));
        }
        return names;
    }

    /**
     * Builds the lookup index. Full names index first; face names of "Front // Back" cards
     * index second and only where free, so a face can never shadow a real standalone card.
     * Package-visible so tests can index a fixture without disk or network.
     */
    void index(List<String> names) {
        Map<String, String> map = new HashMap<>(names.size() * 2);
        for (String name : names) {
            putIfUseful(map, name, name);
        }
        for (String name : names) {
            int split = name.indexOf(" // ");
            if (split < 0) {
                continue;
            }
            for (String face : name.split(" // ")) {
                putIfUseful(map, face, name);
            }
        }
        namesByNormalized = Collections.unmodifiableMap(map);
    }

    private static void putIfUseful(Map<String, String> map, String key, String canonical) {
        String normalized = normalize(key);
        if (normalized.length() >= MIN_QUERY_LENGTH) {
            map.putIfAbsent(normalized, canonical);
        }
    }

    private static String readWholeFile(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file);
                Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())
                        .useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    /** Single replaced file, like the vendor price cache: temp write, then swap into place. */
    private void writeReplacing(String json) throws IOException {
        File temp = new File(cacheFile.getPath() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
        if (cacheFile.exists() && !cacheFile.delete()) {
            throw new IOException("Could not replace the card-name cache at " + cacheFile);
        }
        if (!temp.renameTo(cacheFile)) {
            throw new IOException("Could not move the card-name cache into place at " + cacheFile);
        }
    }
}
