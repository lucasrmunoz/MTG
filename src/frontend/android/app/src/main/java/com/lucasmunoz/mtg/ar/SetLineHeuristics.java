package com.lucasmunoz.mtg.ar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the collector line printed at a card's bottom-left — e.g. "L 0195" over "SPM • EN" — into
 * a set code and collector number, which Scryfall resolves to one exact printing. That precision
 * is what fixes basic lands: "Island" has hundreds of artworks, but "SPM 195" is one card.
 *
 * Pure string logic, unit-testable without ML Kit. OCR noise is expected; the caller validates
 * every pair against Scryfall, so a false positive costs one 404 and nothing else.
 */
final class SetLineHeuristics {

    /** One candidate collector-line reading. */
    static final class SetAndNumber {
        final String setCode;
        final String collectorNumber;

        SetAndNumber(String setCode, String collectorNumber) {
            this.setCode = setCode;
            this.collectorNumber = collectorNumber;
        }
    }

    /**
     * "L 0195", "C 0042b", or a bare "0195" — the rarity letter is optional, leading zeros are
     * padding. Years (19xx/20xx from the copyright line) are excluded for the bare form.
     */
    private static final Pattern NUMBER_LINE =
            Pattern.compile("^(?:[CURML]\\s*)?0*(\\d{1,4})([a-z])?$");

    /** "SPM • EN …" — a short alphanumeric set code followed by a two-letter language. */
    private static final Pattern SET_LINE =
            Pattern.compile("\\b([A-Z0-9]{3,5})\\b\\s*[•·∙*.-]?\\s*\\b([A-Z]{2})\\b");

    private SetLineHeuristics() {}

    /** The collector number in a line, or null. */
    static String parseNumber(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = NUMBER_LINE.matcher(line.trim());
        if (!matcher.matches()) {
            return null;
        }

        String number = matcher.group(1);
        boolean bare = !line.trim().matches("^[CURML].*");
        if (bare && number.length() == 4 && (number.startsWith("19") || number.startsWith("20"))) {
            return null; // Almost certainly the copyright year, not a collector number.
        }
        String suffix = matcher.group(2);
        return suffix == null ? number : number + suffix;
    }

    /** The set code in a line, or null. */
    static String parseSetCode(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = SET_LINE.matcher(line.trim().toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String code = matcher.group(1);
            // The language marker itself ("EN") and pure numbers are not set codes.
            if (code.matches(".*[A-Z].*") && !code.equals(matcher.group(2))) {
                return code.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * Pairs numbers and set codes found across a frame's text lines. Lines are grouped by the
     * caller (usually per OCR block); a lone number and lone set code anywhere in the frame also
     * pair, because the collector line often splits across blocks.
     */
    static List<SetAndNumber> pair(List<String> numbers, List<String> setCodes) {
        List<SetAndNumber> pairs = new ArrayList<>();
        if (numbers.size() == 1 && setCodes.size() == 1) {
            pairs.add(new SetAndNumber(setCodes.get(0), numbers.get(0)));
            return pairs;
        }
        int count = Math.min(numbers.size(), setCodes.size());
        for (int i = 0; i < count; i++) {
            pairs.add(new SetAndNumber(setCodes.get(i), numbers.get(i)));
        }
        return pairs;
    }
}
