package com.lucasmunoz.mtg.ar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vote counter for guided readings: a reading is believed only once enough passes have seen
 * it. OCR misreads flicker — a digit read wrong by one pass names a different printing, but
 * the same wrong digit rarely repeats — so requiring a reading to be reproduced by a later
 * pass drops lone misreads, while the true reading, which every pass repeats, confirms one
 * pass later. Sightings expire so a re-aim starts a fresh vote.
 *
 * Pure and unit-testable: the caller supplies the clock and reports each distinct reading
 * once per pass. Thread safety is internal.
 */
final class GuideConsensus {

    private final int agreeingPasses;
    private final long ttlMs;

    /** Reading key → how many passes saw it and when it was last seen. */
    private final Map<String, Sighting> sightings = new LinkedHashMap<>();

    private static final class Sighting {
        int passes;
        long lastSeenMs;
    }

    GuideConsensus(int agreeingPasses, long ttlMs) {
        this.agreeingPasses = agreeingPasses;
        this.ttlMs = ttlMs;
    }

    /**
     * Records that a pass saw this reading; true once enough passes agree and the reading
     * has earned its lookup.
     */
    synchronized boolean confirm(String key, long nowMs) {
        sightings.values().removeIf(sighting -> nowMs - sighting.lastSeenMs > ttlMs);
        Sighting sighting = sightings.get(key);
        if (sighting == null) {
            sighting = new Sighting();
            sightings.put(key, sighting);
        }
        sighting.passes++;
        sighting.lastSeenMs = nowMs;
        return sighting.passes >= agreeingPasses;
    }

    /** Forgets every sighting — a fresh aim carries no votes over. */
    synchronized void reset() {
        sightings.clear();
    }
}
