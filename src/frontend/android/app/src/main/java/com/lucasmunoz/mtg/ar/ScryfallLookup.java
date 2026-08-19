package com.lucasmunoz.mtg.ar;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The two Scryfall calls the scan mode needs, mirroring the web client's rules
 * (src/lib/sources/scryfall.ts): fuzzy name lookup to turn an OCR'd title into a card, and the
 * distinct-artwork search to collect reference images for tracking.
 */
final class ScryfallLookup {

    private static final String BASE = "https://api.scryfall.com";

    /** A token card this card creates, linked by Scryfall's curated all_parts array. */
    static final class TokenPart {
        final String id;
        final String name;

        TokenPart(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** What scan mode needs to know about a card or printing. */
    static final class CardSummary {
        final String id;
        final String name;
        final String imageUrl;
        /** The card's own keyword abilities, for glossary popups in the AR screen. */
        final List<String> keywords;
        /** Type line and set name for the card list; empty when Scryfall omits them. */
        final String typeLine;
        final String setName;
        /** Rules text (faces joined for double-faced cards); empty when Scryfall omits it. */
        final String oracleText;
        /** The token cards this card creates; empty for the vast majority of cards. */
        final List<TokenPart> tokenParts;

        CardSummary(String id, String name, String imageUrl, List<String> keywords,
                String typeLine, String setName, String oracleText, List<TokenPart> tokenParts) {
            this.id = id;
            this.name = name;
            this.imageUrl = imageUrl;
            this.keywords = keywords;
            this.typeLine = typeLine;
            this.setName = setName;
            this.oracleText = oracleText;
            this.tokenParts = tokenParts;
        }
    }

    private ScryfallLookup() {}

    /**
     * Fuzzy name lookup, which tolerates OCR mangling the way it tolerates misspellings. Null
     * when Scryfall matches nothing (or too many things) — a 404 there is an answer, not an error.
     */
    static CardSummary findByFuzzyName(String title) throws IOException {
        JSONObject json = getJson(BASE + "/cards/named?fuzzy=" + encode(title));
        if (json == null) {
            return null;
        }
        try {
            CardSummary card = parseCard(json);
            return card != null && card.imageUrl != null ? card : null;
        } catch (JSONException e) {
            throw new IOException("Could not parse Scryfall's fuzzy lookup response.", e);
        }
    }

    /**
     * Exact name lookup for a name the local catalog already matched — no fuzzy tolerance
     * needed, the catalog match settled which card it is. Null on the rare 404 (a catalog
     * fresher than the card database).
     */
    static CardSummary findByExactName(String name) throws IOException {
        JSONObject json = getJson(BASE + "/cards/named?exact=" + encode(name));
        if (json == null) {
            return null;
        }
        try {
            CardSummary card = parseCard(json);
            return card != null && card.imageUrl != null ? card : null;
        } catch (JSONException e) {
            throw new IOException("Could not parse Scryfall's exact lookup response.", e);
        }
    }

    /** The card-name catalog's raw JSON — every name Scryfall knows, ~2 MB, cached on disk. */
    static String cardNamesJson() throws IOException {
        String body = getBody(BASE + "/catalog/card-names");
        if (body == null) {
            throw new IOException("Scryfall's card-name catalog returned 404.");
        }
        return body;
    }

    /**
     * The exact printing at a set code and collector number — "spm 195" is one card where a name
     * matches hundreds of artworks. Null when Scryfall knows no such printing, which is how OCR
     * false positives get discarded.
     */
    static CardSummary bySetAndNumber(String setCode, String collectorNumber) throws IOException {
        JSONObject json = getJson(
                BASE + "/cards/" + encode(setCode) + "/" + encode(collectorNumber));
        if (json == null) {
            return null;
        }
        try {
            CardSummary card = parseCard(json);
            return card != null && card.imageUrl != null ? card : null;
        } catch (JSONException e) {
            throw new IOException("Could not parse Scryfall's collector-number response.", e);
        }
    }

    /**
     * One card by Scryfall id — how a token card linked from all_parts gets its scan. Null on
     * 404 (a stale link), IOException on anything else.
     */
    static CardSummary byId(String id) throws IOException {
        JSONObject json = getJson(BASE + "/cards/" + encode(id));
        if (json == null) {
            return null;
        }
        try {
            CardSummary card = parseCard(json);
            return card != null && card.imageUrl != null ? card : null;
        } catch (JSONException e) {
            throw new IOException("Could not parse Scryfall's card-by-id response.", e);
        }
    }

    /** Every printing with distinct artwork, oldest first; the first page is plenty. */
    static List<CardSummary> artVersions(String cardName) throws IOException {
        String query = encode("!\"" + cardName + "\"");
        JSONObject json = getJson(
                BASE + "/cards/search?q=" + query + "&unique=art&order=released&dir=asc");
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return parseArtList(json);
        } catch (JSONException e) {
            throw new IOException("Could not parse Scryfall's art search response.", e);
        }
    }

    /** Package-visible for unit tests. */
    static CardSummary parseCard(JSONObject json) throws JSONException {
        String id = json.optString("id", "");
        String name = json.optString("name", "");
        if (id.isEmpty() || name.isEmpty()) {
            return null;
        }

        List<String> keywords = new ArrayList<>();
        JSONArray keywordArray = json.optJSONArray("keywords");
        if (keywordArray != null) {
            for (int i = 0; i < keywordArray.length(); i++) {
                keywords.add(keywordArray.getString(i));
            }
        }
        return new CardSummary(id, name, imageUrl(json), keywords,
                json.optString("type_line", ""), json.optString("set_name", ""),
                oracleText(json), tokenParts(json, id));
    }

    /** Top-level rules text, or the faces' texts joined — double-faced cards publish per face. */
    private static String oracleText(JSONObject card) {
        String text = card.optString("oracle_text", "");
        if (!text.isEmpty()) {
            return text;
        }
        JSONArray faces = card.optJSONArray("card_faces");
        if (faces == null) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < faces.length(); i++) {
            JSONObject face = faces.optJSONObject(i);
            String faceText = face == null ? "" : face.optString("oracle_text", "");
            if (!faceText.isEmpty()) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(faceText);
            }
        }
        return joined.toString();
    }

    /** The all_parts entries marked as tokens — Scryfall's own "this card creates that" links. */
    private static List<TokenPart> tokenParts(JSONObject card, String cardId) throws JSONException {
        List<TokenPart> parts = new ArrayList<>();
        JSONArray allParts = card.optJSONArray("all_parts");
        if (allParts == null) {
            return parts;
        }
        for (int i = 0; i < allParts.length(); i++) {
            JSONObject part = allParts.getJSONObject(i);
            String partId = part.optString("id", "");
            String partName = part.optString("name", "");
            if ("token".equals(part.optString("component", ""))
                    && !partId.isEmpty() && !partName.isEmpty() && !partId.equals(cardId)) {
                parts.add(new TokenPart(partId, partName));
            }
        }
        return parts;
    }

    /** Package-visible for unit tests. */
    static List<CardSummary> parseArtList(JSONObject json) throws JSONException {
        List<CardSummary> versions = new ArrayList<>();
        JSONArray data = json.optJSONArray("data");
        if (data == null) {
            return versions;
        }
        for (int i = 0; i < data.length(); i++) {
            CardSummary card = parseCard(data.getJSONObject(i));
            if (card != null && card.imageUrl != null) {
                versions.add(card);
            }
        }
        return versions;
    }

    /**
     * Top-level image_uris, falling back to the first face carrying one — double-faced cards
     * publish scans only per face. Mirrors the web client's firstFaceImages.
     */
    private static String imageUrl(JSONObject card) {
        JSONObject images = card.optJSONObject("image_uris");
        if (images != null) {
            String normal = images.optString("normal", "");
            if (!normal.isEmpty()) {
                return normal;
            }
        }
        JSONArray faces = card.optJSONArray("card_faces");
        if (faces != null) {
            for (int i = 0; i < faces.length(); i++) {
                JSONObject faceImages = faces.optJSONObject(i) == null
                        ? null
                        : faces.optJSONObject(i).optJSONObject("image_uris");
                if (faceImages != null) {
                    String normal = faceImages.optString("normal", "");
                    if (!normal.isEmpty()) {
                        return normal;
                    }
                }
            }
        }
        return null;
    }

    /** GET returning parsed JSON, null on 404, IOException on anything else going wrong. */
    private static JSONObject getJson(String url) throws IOException {
        String body = getBody(url);
        if (body == null) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Scryfall returned unparseable JSON for " + url, e);
        }
    }

    /** GET returning the raw body, null on 404, IOException on anything else going wrong. */
    private static String getBody(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        // Scryfall rejects default HTTP-library User-Agents outright ("generic_user_agent").
        connection.setRequestProperty("User-Agent", "MTGCardLookup/1.0 (Android AR)");
        connection.setRequestProperty("Accept", "application/json");

        try {
            if (connection.getResponseCode() == 404) {
                return null;
            }
            if (connection.getResponseCode() != 200) {
                throw new IOException("Scryfall returned " + connection.getResponseCode()
                        + " for " + url);
            }
            try (InputStream stream = connection.getInputStream();
                    Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())
                            .useDelimiter("\\A")) {
                return scanner.hasNext() ? scanner.next() : "";
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
}
