package com.lucasmunoz.mtg.ar;

/**
 * Decides whether an OCR'd line of text could plausibly be a card title worth asking Scryfall
 * about. Pure string logic, kept out of {@link CardIdentifier} so it is unit-testable without ML
 * Kit on the classpath.
 */
final class TitleHeuristics {

    private TitleHeuristics() {}

    /**
     * Cleans an OCR line into a candidate title, or returns null when it cannot be one.
     *
     * Card titles are letters with the odd apostrophe, comma or hyphen. OCR noise — mana cost
     * glyphs, collector numbers, rules-text fragments read edge-on — mostly fails the letter
     * ratio or the length bounds. Scryfall's fuzzy endpoint absorbs the rest.
     */
    static String clean(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim().replaceAll("\\s+", " ");
        // Strip leading/trailing junk that is not part of any name (bullets, pips, quotes).
        trimmed = trimmed.replaceAll("^[^\\p{L}]+", "").replaceAll("[^\\p{L}]+$", "");
        if (trimmed.length() < 3 || trimmed.length() > 40) {
            return null;
        }

        int letters = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetter(trimmed.charAt(i))) {
                letters++;
            }
        }
        if (letters < trimmed.length() * 0.6) {
            return null;
        }

        return trimmed;
    }
}
