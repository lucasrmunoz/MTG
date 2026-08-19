package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TokenCreationTest {

    @Test
    public void readsSpelledOutCount() {
        // Army of the Damned (C18) — the card this feature was built around.
        String text = "Create thirteen tapped 2/2 black Zombie creature tokens.\n"
                + "Flashback {7}{B}{B}{B} (You may cast this card from your graveyard for its "
                + "flashback cost. Then exile it.)";
        assertEquals(13, TokenCreation.countFor(text, "Zombie"));
    }

    @Test
    public void articleMeansOne() {
        assertEquals(1, TokenCreation.countFor(
                "When this creature enters, create a 1/1 white Soldier creature token.",
                "Soldier"));
    }

    @Test
    public void eachNameGetsItsOwnCount() {
        String text = "Create two 1/1 red Goblin creature tokens and a Treasure token.";
        assertEquals(2, TokenCreation.countFor(text, "Goblin"));
        assertEquals(1, TokenCreation.countFor(text, "Treasure"));
    }

    @Test
    public void nameBeforeTheVerbDoesNotShadowTheCreatedOne() {
        assertEquals(2, TokenCreation.countFor(
                "Whenever a Zombie you control dies, create two 2/2 black Zombie creature "
                        + "tokens.",
                "Zombie"));
    }

    @Test
    public void openEndedCountsStayOpen() {
        assertEquals(0, TokenCreation.countFor(
                "Create X 2/2 black Zombie creature tokens.", "Zombie"));
        assertEquals(0, TokenCreation.countFor(
                "Create that many 1/1 white Soldier creature tokens.", "Soldier"));
    }

    @Test
    public void statTextNeverParsesAsACount() {
        // "2/2" sits between the verb and the name; only "four" may win.
        assertEquals(4, TokenCreation.countFor(
                "Create four 2/2 black Zombie creature tokens.", "Zombie"));
    }

    @Test
    public void multiWordTokenNamesMatch() {
        assertEquals(2, TokenCreation.countFor(
                "Create two 1/1 colorless Eldrazi Scion creature tokens.", "Eldrazi Scion"));
    }

    @Test
    public void noCreateSentenceIsOpen() {
        assertEquals(0, TokenCreation.countFor("Destroy target creature.", "Zombie"));
        assertEquals(0, TokenCreation.countFor("", "Zombie"));
    }

    @Test
    public void laterSentenceStillCounts() {
        assertEquals(3, TokenCreation.countFor(
                "Draw a card. Create three 1/1 green Saproling creature tokens.",
                "Saproling"));
    }
}
