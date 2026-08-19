package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CardCountersTest {

    @Test
    public void statCountersMergeByKindAndVanishAtZero() {
        CardCounters counters = new CardCounters();
        counters.addStat(1, 1);
        counters.addStat(1, 1);
        counters.addStat(-1, -1);

        assertEquals(2, counters.stats.size());
        assertEquals(2, counters.stats.get(0).count);
        assertEquals(1, counters.netPower());
        assertEquals(1, counters.netToughness());

        counters.removeStat(1, 1);
        counters.removeStat(1, 1);
        counters.removeStat(-1, -1);
        assertTrue(counters.stats.isEmpty());
        assertTrue(counters.isEmpty());
    }

    @Test
    public void allFourSignCombinationsCoexist() {
        CardCounters counters = new CardCounters();
        counters.addStat(2, 2);
        counters.addStat(-3, -3);
        counters.addStat(1, -1);
        counters.addStat(-1, 1);

        assertEquals(4, counters.stats.size());
        assertEquals(-1, counters.netPower());
        assertEquals(-1, counters.netToughness());
        assertEquals("+2/+2", counters.stats.get(0).label());
        assertEquals("-3/-3", counters.stats.get(1).label());
        assertEquals("+1/-1", counters.stats.get(2).label());
        assertEquals("-1/+1", counters.stats.get(3).label());
    }

    @Test
    public void zeroZeroStatIsMeaninglessAndIgnored() {
        CardCounters counters = new CardCounters();
        counters.addStat(0, 0);
        counters.applyStatDelta(0, 0);
        assertTrue(counters.isEmpty());
    }

    @Test
    public void statDeltaPeelsMirroredCountersBeforeMintingTheOpposite() {
        CardCounters counters = new CardCounters();
        counters.addStat(1, 1);
        counters.addStat(1, 1);

        counters.applyStatDelta(-1, -1);
        assertEquals(1, counters.stats.get(0).count);

        counters.applyStatDelta(-1, -1);
        assertTrue(counters.stats.isEmpty());

        counters.applyStatDelta(-1, -1);
        assertEquals("-1/-1", counters.stats.get(0).label());
        assertEquals(1, counters.stats.get(0).count);
    }

    @Test
    public void statDeltaOnlyPeelsExactMirrors() {
        CardCounters counters = new CardCounters();
        counters.addStat(2, 2);

        counters.applyStatDelta(-1, -1);
        assertEquals(2, counters.stats.size());
        assertEquals("+2/+2", counters.stats.get(0).label());
        assertEquals("-1/-1", counters.stats.get(1).label());

        counters.applyStatDelta(-2, -2);
        assertEquals(1, counters.stats.size());
        assertEquals("-1/-1", counters.stats.get(0).label());
    }

    @Test
    public void statDeltaAddsFreshAsymmetricKinds() {
        CardCounters counters = new CardCounters();
        counters.applyStatDelta(2, 0);
        counters.applyStatDelta(2, 0);
        counters.applyStatDelta(0, -1);

        assertEquals(2, counters.stats.size());
        assertEquals("+2/+0", counters.stats.get(0).label());
        assertEquals(2, counters.stats.get(0).count);
        assertEquals("+0/-1", counters.stats.get(1).label());

        counters.applyStatDelta(-2, 0);
        assertEquals(1, counters.stats.get(0).count);
    }

    @Test
    public void clearStatsDropsCountersButKeepsKeywords() {
        CardCounters counters = new CardCounters();
        counters.addKeyword("Flying");
        counters.addStat(1, 1);
        counters.addStat(-2, 3);

        counters.clearStats();
        assertTrue(counters.stats.isEmpty());
        assertEquals(1, counters.keywords.size());
    }

    @Test
    public void keywordsDedupeCaseInsensitively() {
        CardCounters counters = new CardCounters();
        counters.addKeyword("Flying");
        counters.addKeyword("flying");
        counters.addKeyword("  ");
        assertEquals(1, counters.keywords.size());

        counters.removeKeyword("FLYING");
        assertTrue(counters.keywords.isEmpty());
    }

    @Test
    public void commanderTaxIsTwoPerCast() {
        CardCounters counters = new CardCounters();
        assertEquals(0, counters.commanderTax());
        counters.commanderCasts = 3;
        assertEquals(6, counters.commanderTax());
        assertFalse(counters.isEmpty());
    }

    @Test
    public void jsonRoundTripPreservesEverything() throws Exception {
        CardCounters counters = new CardCounters();
        counters.addKeyword("Flying");
        counters.addKeyword("Deathtouch");
        counters.addStat(1, 1);
        counters.addStat(1, 1);
        counters.addStat(-2, 3);
        counters.commanderCasts = 2;

        CardCounters restored = CardCounters.fromJson(
                new JSONObject(counters.toJson().toString()));

        assertEquals(counters.keywords, restored.keywords);
        assertEquals(2, restored.stats.size());
        assertEquals(2, restored.stats.get(0).count);
        assertEquals("-2/+3", restored.stats.get(1).label());
        assertEquals(2, restored.commanderCasts);
        assertEquals(4, restored.commanderTax());
    }

    @Test
    public void malformedCountsAreDropped() throws Exception {
        JSONObject json = new JSONObject(
                "{\"keywords\":[],\"stats\":[{\"power\":1,\"toughness\":1,\"count\":0}],"
                        + "\"commanderCasts\":-4}");
        CardCounters restored = CardCounters.fromJson(json);
        assertTrue(restored.isEmpty());
    }
}
