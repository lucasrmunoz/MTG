package com.lucasmunoz.mtg.ar;

import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Reads card titles off camera frames and turns them into candidate cards.
 *
 * On-device OCR (ML Kit) pulls text lines out of a frame; lines that could be a title go through
 * Scryfall's fuzzy lookup, which forgives OCR misreads the way it forgives typos. Every distinct
 * hit becomes a candidate for the user to tap. This never guesses from card *shape* — a match
 * only exists once Scryfall confirms a real card name, and tracking still runs on registered
 * reference scans afterwards.
 */
final class CardIdentifier {

    interface Listener {
        /** Called off the main thread each time the candidate list grows. */
        void onCandidates(List<ScryfallLookup.CardSummary> candidates);
    }

    private static final String TAG = "CardIdentifier";

    /** How often to OCR a frame; more brings no benefit at hand-held steadiness. */
    private static final long ATTEMPT_INTERVAL_MS = 700;

    /** The slower cadence once a card is active, when scanning only watches for a switch. */
    private static final long RELAXED_INTERVAL_MS = 2000;

    /**
     * The camera sensor is landscape while the activity is locked to portrait, so frames reach
     * ML Kit rotated by 90 degrees.
     */
    private static final int ROTATION_DEGREES = 90;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ExecutorService lookupExecutor;
    private final Listener listener;

    private volatile boolean recognizing;
    private volatile long intervalMs = ATTEMPT_INTERVAL_MS;
    private long lastAttemptMs;

    /** One recognised card, remembering whether it came from the exact collector line. */
    private static final class Match {
        final ScryfallLookup.CardSummary card;
        final boolean exact;

        Match(ScryfallLookup.CardSummary card, boolean exact) {
            this.card = card;
            this.exact = exact;
        }
    }

    /** Readings Scryfall already rejected, so noise lines are not re-queried every frame. */
    private final Set<String> rejected = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    /**
     * Candidates keyed by card name. A collector-line hit ("SPM 195") names the exact printing
     * on the table and replaces a fuzzy name hit for the same card, which for a basic land would
     * otherwise float arbitrary artwork.
     */
    private final Map<String, Match> matchedByName = new LinkedHashMap<>();

    CardIdentifier(ExecutorService lookupExecutor, Listener listener) {
        this.lookupExecutor = lookupExecutor;
        this.listener = listener;
    }

    /** Slows the scan cadence once a card is active; watching for a switch needs less urgency. */
    void setRelaxed(boolean relaxed) {
        intervalMs = relaxed ? RELAXED_INTERVAL_MS : ATTEMPT_INTERVAL_MS;
    }

    /** Everything recognised so far, for rebuilding the chip row after a card switch. */
    synchronized List<ScryfallLookup.CardSummary> snapshot() {
        List<ScryfallLookup.CardSummary> cards = new ArrayList<>();
        for (Match match : matchedByName.values()) {
            cards.add(match.card);
        }
        return cards;
    }

    /** Called from the GL thread once per rendered frame; does nothing most of the time. */
    void maybeIdentify(Frame frame) {
        long now = SystemClock.elapsedRealtime();
        if (recognizing || now - lastAttemptMs < intervalMs) {
            return;
        }

        Image image;
        try {
            image = frame.acquireCameraImage();
        } catch (NotYetAvailableException e) {
            return;
        }

        lastAttemptMs = now;
        recognizing = true;
        InputImage input = InputImage.fromMediaImage(image, ROTATION_DEGREES);
        recognizer.process(input)
                .addOnSuccessListener(this::handleText)
                .addOnCompleteListener(task -> {
                    // The Image backs InputImage until processing completes; close it only now.
                    image.close();
                    recognizing = false;
                });
    }

    private void handleText(Text text) {
        List<String> numbers = new ArrayList<>();
        List<String> setCodes = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            if (block.getLines().isEmpty()) {
                continue;
            }
            // A card's title is the first line of its own text block; deeper lines are type
            // lines and rules text, which fuzzy lookup would happily mis-match.
            String title = TitleHeuristics.clean(block.getLines().get(0).getText());
            if (title != null) {
                scheduleTitleLookup(title);
            }

            // The collector line at the card's bottom carries the exact printing; its number and
            // set code often land in separate lines or blocks, so both are collected frame-wide.
            for (Text.Line line : block.getLines()) {
                String number = SetLineHeuristics.parseNumber(line.getText());
                if (number != null && !numbers.contains(number)) {
                    numbers.add(number);
                }
                String setCode = SetLineHeuristics.parseSetCode(line.getText());
                if (setCode != null && !setCodes.contains(setCode)) {
                    setCodes.add(setCode);
                }
            }
        }

        for (SetLineHeuristics.SetAndNumber pair : SetLineHeuristics.pair(numbers, setCodes)) {
            schedulePrintingLookup(pair);
        }
    }

    private void scheduleTitleLookup(String title) {
        String key = "title:" + title.toLowerCase();
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
        }
        lookupExecutor.execute(() -> lookUp(key, false, () -> ScryfallLookup.findByFuzzyName(title)));
    }

    private void schedulePrintingLookup(SetLineHeuristics.SetAndNumber pair) {
        String key = "printing:" + pair.setCode + "/" + pair.collectorNumber;
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
        }
        lookupExecutor.execute(() -> lookUp(key, true,
                () -> ScryfallLookup.bySetAndNumber(pair.setCode, pair.collectorNumber)));
    }

    private interface Lookup {
        ScryfallLookup.CardSummary run() throws IOException;
    }

    private void lookUp(String key, boolean exact, Lookup lookup) {
        try {
            ScryfallLookup.CardSummary card = lookup.run();
            List<ScryfallLookup.CardSummary> snapshot = null;
            synchronized (this) {
                pending.remove(key);
                if (card == null) {
                    rememberRejection(key);
                } else {
                    snapshot = addMatch(card, exact);
                }
            }
            if (snapshot != null) {
                listener.onCandidates(snapshot);
            }
        } catch (IOException e) {
            // Network trouble: forget the attempt so a later frame can retry this reading.
            Log.w(TAG, "Scryfall lookup failed for " + key, e);
            synchronized (this) {
                pending.remove(key);
            }
        }
    }

    /** Records a hit; an exact printing displaces a fuzzy hit for the same card name. */
    private List<ScryfallLookup.CardSummary> addMatch(
            ScryfallLookup.CardSummary card, boolean exact) {
        String nameKey = card.name.toLowerCase();
        Match existing = matchedByName.get(nameKey);
        if (existing != null && (existing.exact || !exact)) {
            return null;
        }
        matchedByName.put(nameKey, new Match(card, exact));

        List<ScryfallLookup.CardSummary> snapshot = new ArrayList<>();
        for (Match match : matchedByName.values()) {
            snapshot.add(match.card);
        }
        return snapshot;
    }

    private void rememberRejection(String key) {
        // Bounded: glare produces endless unique garbage lines on a long session.
        if (rejected.size() >= 64) {
            rejected.clear();
        }
        rejected.add(key);
    }

    void close() {
        recognizer.close();
    }
}
