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
 * Weak readings can also be held to a minimum sighting span: votes alone pass anything that
 * reads stably for two consecutive passes, which the aim-settling window produces plenty of.
 * A span requirement makes such a reading persist long enough for stronger evidence — the
 * exactly-read title that suppresses it — to show up first.
 *
 * Pure and unit-testable: the caller supplies the clock and reports each distinct reading
 * once per pass. Thread safety is internal.
 */
final class GuideConsensus {

    private final int agreeingPasses;
    private final long ttlMs;

    /** Reading key → how many passes saw it and when it was first and last seen. */
    private final Map<String, Sighting> sightings = new LinkedHashMap<>();

    private static final class Sighting {
        int passes;
        long firstSeenMs;
        long lastSeenMs;
    }

    GuideConsensus(int agreeingPasses, long ttlMs) {
        this.agreeingPasses = agreeingPasses;
        this.ttlMs = ttlMs;
    }

    /**
     * Records that a pass saw this reading; true once enough passes agree and the sightings
     * span at least {@code minSpanMs} — zero for readings trusted on votes alone.
     */
    synchronized boolean confirm(String key, long nowMs, long minSpanMs) {
        sightings.values().removeIf(sighting -> nowMs - sighting.lastSeenMs > ttlMs);
        Sighting sighting = sightings.get(key);
        if (sighting == null) {
            sighting = new Sighting();
            sighting.firstSeenMs = nowMs;
            sightings.put(key, sighting);
        }
        sighting.passes++;
        sighting.lastSeenMs = nowMs;
        return sighting.passes >= agreeingPasses && nowMs - sighting.firstSeenMs >= minSpanMs;
    }

    /**
     * True when any live reading under the prefix other than {@code selfKey} was seen at or
     * after {@code sinceMs}. In a one-card context a fresh rival reading means the box's
     * winner is not settled yet — the rival may be the true title and self the misread.
     */
    synchronized boolean rivalSeenSince(String prefix, String selfKey, long sinceMs,
            long nowMs) {
        sightings.values().removeIf(sighting -> nowMs - sighting.lastSeenMs > ttlMs);
        for (Map.Entry<String, Sighting> entry : sightings.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getKey().equals(selfKey)) {
                continue;
            }
            if (entry.getValue().lastSeenMs >= sinceMs) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every sighting — a fresh aim carries no votes over. */
    synchronized void reset() {
        sightings.clear();
    }
}
