package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TitleHeuristicsTest {

    @Test
    public void realTitlesSurvive() {
        assertEquals("Lightning Bolt", TitleHeuristics.clean("Lightning Bolt"));
        assertEquals("Urza's Saga", TitleHeuristics.clean("  Urza's Saga "));
        assertEquals("Minsc & Boo, Timeless Heroes",
                TitleHeuristics.clean("Minsc & Boo, Timeless Heroes"));
    }

    @Test
    public void edgeJunkIsStripped() {
        assertEquals("Snapcaster Mage", TitleHeuristics.clean("• Snapcaster Mage 2"));
        assertEquals("Delver of Secrets", TitleHeuristics.clean("\"Delver of Secrets\""));
    }

    @Test
    public void noiseIsRejected() {
        assertNull(TitleHeuristics.clean(null));
        assertNull(TitleHeuristics.clean(""));
        assertNull(TitleHeuristics.clean("2/2"));
        assertNull(TitleHeuristics.clean("3 4 5 6 7 8"));
        assertNull(TitleHeuristics.clean("ab"));
        assertNull(TitleHeuristics.clean(
                "this line is far far far too long to ever be the name of a magic card"));
    }

    @Test
    public void whitespaceCollapses() {
        assertEquals("Serra Angel", TitleHeuristics.clean("Serra   Angel"));
    }
}
