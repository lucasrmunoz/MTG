package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuideConsensusTest {

    @Test
    public void firstSightingIsNotBelieved() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0, 0));
    }

    @Test
    public void secondPassConfirmsAndBeliefPersists() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0, 0));
        assertTrue(consensus.confirm("name:island", 350, 0));
        assertTrue(consensus.confirm("name:island", 700, 0));
    }

    @Test
    public void loneMisreadNeverConfirmsWhileTrueReadingDoes() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("printing:spm/195", 0, 0));
        assertFalse(consensus.confirm("printing:spm/135", 350, 0));
        assertTrue(consensus.confirm("printing:spm/195", 700, 0));
    }

    @Test
    public void distinctReadingsVoteIndependently() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0, 0));
        assertFalse(consensus.confirm("name:forest", 350, 0));
        assertTrue(consensus.confirm("name:island", 700, 0));
        assertTrue(consensus.confirm("name:forest", 700, 0));
    }

    @Test
    public void minSpanHoldsAReadingUntilSightingsOutlastIt() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        // Wins the vote at the second pass, but 1.5 s must elapse from the first sighting.
        assertFalse(consensus.confirm("name:lands", 0, 1500));
        assertFalse(consensus.confirm("name:lands", 350, 1500));
        assertFalse(consensus.confirm("name:lands", 700, 1500));
        assertTrue(consensus.confirm("name:lands", 1500, 1500));
    }

    @Test
    public void spanAppliesFromFirstSightingNotFromConfirmation() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("fuzzy:hand to hand", 0, 1500));
        // A sparse second sighting past the span confirms — votes and span are independent.
        assertTrue(consensus.confirm("fuzzy:hand to hand", 1600, 1500));
    }

    @Test
    public void freshRivalIsSeenAndStaleRivalIsNot() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        consensus.confirm("name:stand // deliver", 0, 1200);
        consensus.confirm("name:island", 350, 1200);
        // From island's view at 700, the rival was seen 700 ms ago — inside a 1000 ms hold.
        assertTrue(consensus.rivalSeenSince("name:", "name:island", 700 - 1000, 700));
        // By 1600 that sighting is 1600 ms old — stale, island stands alone.
        assertFalse(consensus.rivalSeenSince("name:", "name:island", 1600 - 1000, 1600));
    }

    @Test
    public void rivalCheckIgnoresSelfOtherPrefixesAndExpiredSightings() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        consensus.confirm("name:island", 0, 0);
        consensus.confirm("printing:spm/195", 0, 0);
        assertFalse(consensus.rivalSeenSince("name:", "name:island", -1000, 0));
        consensus.confirm("name:stand // deliver", 100, 0);
        assertTrue(consensus.rivalSeenSince("name:", "name:island", 0, 200));
        // Past the 5 s TTL the rival's sighting is gone entirely.
        assertFalse(consensus.rivalSeenSince("name:", "name:island", 0, 6000));
    }

    @Test
    public void staleSightingsExpireIntoAFreshVote() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0, 0));
        assertFalse(consensus.confirm("name:island", 6000, 0));
        assertTrue(consensus.confirm("name:island", 6350, 0));
    }

    @Test
    public void resetStartsAFreshVote() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0, 0));
        consensus.reset();
        assertFalse(consensus.confirm("name:island", 350, 0));
        assertTrue(consensus.confirm("name:island", 700, 0));
    }
}
