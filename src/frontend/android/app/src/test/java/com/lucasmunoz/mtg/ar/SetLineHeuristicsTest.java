package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SetLineHeuristicsTest {

    @Test
    public void readsRarityAndPaddedNumber() {
        assertEquals("195", SetLineHeuristics.parseNumber("L 0195"));
        assertEquals("42", SetLineHeuristics.parseNumber("C 0042"));
        assertEquals("7a", SetLineHeuristics.parseNumber("U 0007a"));
        assertEquals("195", SetLineHeuristics.parseNumber("0195"));
    }

    @Test
    public void yearsAndNoiseAreNotNumbers() {
        assertNull(SetLineHeuristics.parseNumber("2025"));
        assertNull(SetLineHeuristics.parseNumber("1997"));
        assertNull(SetLineHeuristics.parseNumber("Basic Land — Island"));
        assertNull(SetLineHeuristics.parseNumber("2/2"));
        assertNull(SetLineHeuristics.parseNumber(null));
    }

    @Test
    public void readsSetCodeBeforeArtistCredit() {
        assertEquals("spm", SetLineHeuristics.parseSetCode("SPM • EN Jonas De Ro"));
        assertEquals("spm", SetLineHeuristics.parseSetCode("spm · en"));
        assertEquals("mh3", SetLineHeuristics.parseSetCode("MH3 EN"));
    }

    @Test
    public void proseAndCopyrightAreNotSetCodes() {
        assertNull(SetLineHeuristics.parseSetCode("Basic Land — Island"));
        assertNull(SetLineHeuristics.parseSetCode("TM & © 2025 Wizards of the Coast"));
        assertNull(SetLineHeuristics.parseSetCode(null));
    }

    @Test
    public void lonePairMatchesAcrossBlocks() {
        List<SetLineHeuristics.SetAndNumber> pairs = SetLineHeuristics.pair(
                Collections.singletonList("195"), Collections.singletonList("spm"));
        assertEquals(1, pairs.size());
        assertEquals("spm", pairs.get(0).setCode);
        assertEquals("195", pairs.get(0).collectorNumber);
    }

    @Test
    public void unevenListsPairUpToTheShorter() {
        List<SetLineHeuristics.SetAndNumber> pairs = SetLineHeuristics.pair(
                Arrays.asList("195", "12"), Collections.singletonList("spm"));
        assertEquals(1, pairs.size());
        assertEquals(0, SetLineHeuristics.pair(Collections.emptyList(),
                Collections.singletonList("spm")).size());
    }
}
