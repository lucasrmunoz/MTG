package com.lucasmunoz.mtg.ar;

import java.util.Locale;

/**
 * Reads how many of a named token a card's rules text creates. Which token a card makes comes
 * from Scryfall's all_parts links, never from here — this only settles the count, because
 * oracle text spells it out in words: "Create thirteen tapped 2/2 black Zombie creature tokens."
 *
 * Returns 0 when the text does not settle it — "create X", "create that many", or no
 * recognisable create sentence — so the caller can ask the user instead of guessing.
 */
final class TokenCreation {

    private static final String[] NUMBER_WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty",
    };

    private TokenCreation() {}

    /**
     * The count of {@code tokenName} tokens the text creates, or 0 when the text leaves it
     * open. Works sentence by sentence: after the "create" verb, the count word nearest before
     * the token's name is its count — so "create two Zombies and a Treasure" answers each name
     * with its own number, and a Zombie named before the verb ("whenever a Zombie dies, …")
     * never shadows the created one.
     */
    static int countFor(String oracleText, String tokenName) {
        String[] nameWords = tokenName.toLowerCase(Locale.ROOT).split("\\s+");
        for (String sentence : oracleText.split("[.\n]")) {
            // Keeps 2/2 as one word so stat text can never parse as a count.
            String[] words = sentence.toLowerCase(Locale.ROOT).split("[^a-z0-9/']+");
            int createAt = indexOfCreate(words);
            if (createAt < 0) {
                continue;
            }
            int nameAt = indexOfName(words, nameWords, createAt + 1);
            if (nameAt < 0) {
                continue;
            }
            for (int i = nameAt - 1; i > createAt; i--) {
                int count = wordToCount(words[i]);
                if (count > 0) {
                    return count;
                }
            }
            return 0; // "create that many …", "create X …": present but unsettled.
        }
        return 0;
    }

    private static int indexOfCreate(String[] words) {
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals("create") || words[i].equals("creates")) {
                return i;
            }
        }
        return -1;
    }

    /** First occurrence of the name's word sequence at or after {@code from}; plurals match. */
    private static int indexOfName(String[] words, String[] nameWords, int from) {
        for (int i = from; i + nameWords.length <= words.length; i++) {
            boolean matches = true;
            for (int j = 0; j < nameWords.length; j++) {
                String word = words[i + j];
                String name = nameWords[j];
                if (!word.equals(name) && !word.equals(name + "s")) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return i;
            }
        }
        return -1;
    }

    /** "a"/"an" mean one; number words and numerals mean themselves; anything else is not a
     *  count ("tapped", "black", "2/2", "x" all fall through). */
    private static int wordToCount(String word) {
        if (word.equals("a") || word.equals("an")) {
            return 1;
        }
        for (int i = 1; i < NUMBER_WORDS.length; i++) {
            if (NUMBER_WORDS[i].equals(word)) {
                return i;
            }
        }
        if (!word.matches("\\d+")) {
            return 0;
        }
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException e) {
            return 0; // More digits than an int holds; nothing on a card gets close.
        }
    }
}
