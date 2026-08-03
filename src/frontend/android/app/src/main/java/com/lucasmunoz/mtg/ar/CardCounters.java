package com.lucasmunoz.mtg.ar;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Everything sitting on one physical card: keyword counters, stat counters, and how often its
 * owner has cast it from the command zone (commander tax is two generic per prior cast).
 *
 * Stat counters cover every sign combination — +X/+X, -X/-X, +X/-X, -X/+X — as (power, toughness)
 * pairs with a count, so three +1/+1 counters are one entry with count 3.
 */
public final class CardCounters {

    /** One kind of stat counter and how many of it are on the card. */
    public static final class StatCounter {
        public final int power;
        public final int toughness;
        public int count;

        public StatCounter(int power, int toughness, int count) {
            this.power = power;
            this.toughness = toughness;
            this.count = count;
        }

        /** Formats the kind alone, e.g. "+1/+1" or "-2/+3". */
        public String label() {
            return (power >= 0 ? "+" + power : String.valueOf(power))
                    + "/"
                    + (toughness >= 0 ? "+" + toughness : String.valueOf(toughness));
        }
    }

    public final List<String> keywords = new ArrayList<>();
    public final List<StatCounter> stats = new ArrayList<>();
    public int commanderCasts;

    /** Adds one keyword counter. A card either has a keyword counter or it does not, so no dupes. */
    public void addKeyword(String keyword) {
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : keywords) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        keywords.add(trimmed);
    }

    public void removeKeyword(String keyword) {
        for (int i = 0; i < keywords.size(); i++) {
            if (keywords.get(i).equalsIgnoreCase(keyword)) {
                keywords.remove(i);
                return;
            }
        }
    }

    /** Adds one counter of the given kind, merging with an existing entry of the same kind. */
    public void addStat(int power, int toughness) {
        if (power == 0 && toughness == 0) {
            return;
        }
        for (StatCounter stat : stats) {
            if (stat.power == power && stat.toughness == toughness) {
                stat.count++;
                return;
            }
        }
        stats.add(new StatCounter(power, toughness, 1));
    }

    /** Removes one counter of the given kind; the entry disappears when none are left. */
    public void removeStat(int power, int toughness) {
        for (int i = 0; i < stats.size(); i++) {
            StatCounter stat = stats.get(i);
            if (stat.power == power && stat.toughness == toughness) {
                stat.count--;
                if (stat.count <= 0) {
                    stats.remove(i);
                }
                return;
            }
        }
    }

    public int netPower() {
        int sum = 0;
        for (StatCounter stat : stats) {
            sum += stat.power * stat.count;
        }
        return sum;
    }

    public int netToughness() {
        int sum = 0;
        for (StatCounter stat : stats) {
            sum += stat.toughness * stat.count;
        }
        return sum;
    }

    /** The extra generic mana this commander costs right now: two per previous cast. */
    public int commanderTax() {
        return commanderCasts * 2;
    }

    /** True when there is nothing worth persisting, so the store can drop the entry. */
    public boolean isEmpty() {
        return keywords.isEmpty() && stats.isEmpty() && commanderCasts == 0;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("keywords", new JSONArray(keywords));

        JSONArray statArray = new JSONArray();
        for (StatCounter stat : stats) {
            JSONObject entry = new JSONObject();
            entry.put("power", stat.power);
            entry.put("toughness", stat.toughness);
            entry.put("count", stat.count);
            statArray.put(entry);
        }
        json.put("stats", statArray);
        json.put("commanderCasts", commanderCasts);
        return json;
    }

    public static CardCounters fromJson(JSONObject json) throws JSONException {
        CardCounters counters = new CardCounters();

        JSONArray keywordArray = json.optJSONArray("keywords");
        if (keywordArray != null) {
            for (int i = 0; i < keywordArray.length(); i++) {
                counters.addKeyword(keywordArray.getString(i));
            }
        }

        JSONArray statArray = json.optJSONArray("stats");
        if (statArray != null) {
            for (int i = 0; i < statArray.length(); i++) {
                JSONObject entry = statArray.getJSONObject(i);
                int count = entry.getInt("count");
                if (count > 0) {
                    counters.stats.add(new StatCounter(
                            entry.getInt("power"), entry.getInt("toughness"), count));
                }
            }
        }

        counters.commanderCasts = Math.max(0, json.optInt("commanderCasts", 0));
        return counters;
    }
}
