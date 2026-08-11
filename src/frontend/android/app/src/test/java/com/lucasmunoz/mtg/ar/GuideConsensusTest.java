package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuideConsensusTest {

    @Test
    public void firstSightingIsNotBelieved() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0));
    }

    @Test
    public void secondPassConfirmsAndBeliefPersists() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0));
        assertTrue(consensus.confirm("name:island", 350));
        assertTrue(consensus.confirm("name:island", 700));
    }

    @Test
    public void loneMisreadNeverConfirmsWhileTrueReadingDoes() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("printing:spm/195", 0));
        assertFalse(consensus.confirm("printing:spm/135", 350));
        assertTrue(consensus.confirm("printing:spm/195", 700));
    }

    @Test
    public void distinctReadingsVoteIndependently() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0));
        assertFalse(consensus.confirm("name:forest", 350));
        assertTrue(consensus.confirm("name:island", 700));
        assertTrue(consensus.confirm("name:forest", 700));
    }

    @Test
    public void staleSightingsExpireIntoAFreshVote() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0));
        assertFalse(consensus.confirm("name:island", 6000));
        assertTrue(consensus.confirm("name:island", 6350));
    }

    @Test
    public void resetStartsAFreshVote() {
        GuideConsensus consensus = new GuideConsensus(2, 5000);
        assertFalse(consensus.confirm("name:island", 0));
        consensus.reset();
        assertFalse(consensus.confirm("name:island", 350));
        assertTrue(consensus.confirm("name:island", 700));
    }
}
