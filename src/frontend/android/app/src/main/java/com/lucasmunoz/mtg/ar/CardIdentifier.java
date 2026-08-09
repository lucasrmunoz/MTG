package com.lucasmunoz.mtg.ar;

import android.graphics.Rect;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import com.google.ar.core.Coordinates2d;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Reads whole cards off camera frames and turns them into candidate cards.
 *
 * On-device OCR (ML Kit) pulls text lines out of a frame; every line is matched locally
 * against the cached Scryfall name catalog, so a card identifies in one pass without a
 * network round trip per reading. Only a name the catalog actually confirms costs a network
 * call — the exact-name fetch that brings images and details. This never guesses from card
 * *shape*, and an ambiguous read matches nothing rather than picking a card. Wrong reads are
 * correctable: a removed card's name stays out until {@link #rescan} deliberately reopens
 * everything.
 */
final class CardIdentifier {

    interface Listener {
        /** Called off the main thread each time the candidate list grows. */
        void onCandidates(List<ScryfallLookup.CardSummary> candidates);
    }

    interface ScanListener {
        /**
         * Live scanning feedback, called off the main thread. After each OCR pass, outlines
         * holds a view-space 4-corner polygon (8 floats) per text line being read;
         * when a lookup settles the call carries null outlines (the ones on screen
         * are still current) with the refreshed pending list.
         */
        void onScanActivity(List<float[]> outlines, List<String> pendingTitles);
    }

    private static final String TAG = "CardIdentifier";

    /** How often to OCR a frame; more brings no benefit at hand-held steadiness. */
    private static final long ATTEMPT_INTERVAL_MS = 700;

    /** Guide mode polls near back-to-back: the user is actively holding a card to be read. */
    private static final long GUIDE_ATTEMPT_INTERVAL_MS = 350;

    /**
     * The camera sensor is landscape while the activity is locked to portrait, so frames reach
     * ML Kit rotated by 90 degrees. The corpus recorder stamps the same value into saved frames.
     */
    static final int ROTATION_DEGREES = 90;

    /** Reads reach this far beyond the outline, forgiving a card that overflows it. */
    private static final float GUIDE_MARGIN_FRAC = 0.10f;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ExecutorService lookupExecutor;
    private final CardNameCatalog catalog;
    private final Listener listener;
    private final ScanListener scanListener;

    private volatile boolean recognizing;
    private long lastAttemptMs;

    /**
     * View-space {left, top, right, bottom} of the on-screen guide box, or null for ambient
     * scanning. While set, reading is confined to the box: every line inside it is matched
     * against the whole-card name catalog and the collector line is parsed from the same
     * lines, all describing the one aimed card — never frame-wide.
     */
    private volatile float[] guideBox;

    /**
     * Card names the guide box matched recently, by when they were last seen. Both reads in
     * the box describe the same physical card, so a collector-line hit must agree with a
     * matched name to be adopted — a misread digit names a different card, and the name is
     * the tiebreak. Empty means no name is readable (foil glare); then the collector line
     * stands alone. Fed by instant local catalog matches, so the old race against a slow
     * network title lookup is gone. Guarded by this.
     */
    private final Map<String, Long> guideNamesSeenAtMs = new LinkedHashMap<>();

    /** A box name older than this is stale — the user has re-aimed since. */
    private static final long GUIDE_NAME_TTL_MS = 5000;

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
    /** What each pending lookup is about, human-readable, for the "checking …" status line. */
    private final Map<String, String> pendingDisplay = new LinkedHashMap<>();
    /**
     * Candidates keyed by card name. A collector-line hit ("SPM 195") names the exact printing
     * on the table and replaces a name hit for the same card, which for a basic land would
     * otherwise float arbitrary artwork.
     */
    private final Map<String, Match> matchedByName = new LinkedHashMap<>();

    /** Names the user removed. Without this, the next lookup replays the match and the card
     *  rejoins within a frame — a removal silences the name until the next {@link #rescan}. */
    private final Set<String> dismissedNames = new HashSet<>();

    CardIdentifier(ExecutorService lookupExecutor, CardNameCatalog catalog, Listener listener,
            ScanListener scanListener) {
        this.lookupExecutor = lookupExecutor;
        this.catalog = catalog;
        this.listener = listener;
        this.scanListener = scanListener;
    }

    /** Confines scanning to a view-space box, or null to return to ambient frame-wide reads. */
    void setGuideBox(float[] viewBox) {
        this.guideBox = viewBox;
        synchronized (this) {
            // Either direction is a fresh aim; names from before the toggle prove nothing.
            guideNamesSeenAtMs.clear();
        }
    }

    /** Called from the GL thread once per rendered frame; does nothing most of the time. */
    void maybeIdentify(Frame frame) {
        long now = SystemClock.elapsedRealtime();
        long interval = guideBox != null ? GUIDE_ATTEMPT_INTERVAL_MS : ATTEMPT_INTERVAL_MS;
        if (recognizing || now - lastAttemptMs < interval) {
            return;
        }

        Image image;
        try {
            image = frame.acquireCameraImage();
        } catch (NotYetAvailableException e) {
            return;
        }

        // Where the sensor image lands on screen, captured now — the frame is only valid on
        // this thread during this update. Three corners pin down the affine display transform.
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        float[] cornerViews = new float[6];
        frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                new float[] {0, 0, imageWidth, 0, 0, imageHeight},
                Coordinates2d.VIEW,
                cornerViews);

        lastAttemptMs = now;
        recognizing = true;
        // Snapshot: the toggle must not switch modes between recognition and handling.
        float[] guide = guideBox;
        InputImage input = InputImage.fromMediaImage(image, ROTATION_DEGREES);
        recognizer.process(input)
                .addOnSuccessListener(text ->
                        handleText(text, imageWidth, imageHeight, cornerViews, guide))
                .addOnCompleteListener(task -> {
                    // The Image backs InputImage until processing completes; close it only now.
                    image.close();
                    recognizing = false;
                });
    }

    /**
     * Collects the pass's readings and hands them to the resolver on the lookup thread —
     * catalog matching over tens of thousands of names is milliseconds of work, but not
     * main-thread milliseconds.
     */
    private void handleText(
            Text text, int imageWidth, int imageHeight, float[] cornerViews, float[] guide) {
        List<float[]> outlines = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        List<String> setCodes = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            if (block.getLines().isEmpty()) {
                continue;
            }
            if (guide != null) {
                readGuidedBlock(block, imageWidth, imageHeight, cornerViews, guide,
                        outlines, lines, numbers, setCodes);
                continue;
            }

            // A card's title is the first line of its own text block; deeper lines are type
            // lines and rules text, which name matching would have to wade through for nothing.
            String title = TitleHeuristics.clean(block.getLines().get(0).getText());
            if (title != null) {
                Rect box = block.getBoundingBox();
                if (box != null) {
                    outlines.add(viewQuadFor(box, imageWidth, imageHeight, cornerViews));
                }
                lines.add(title);
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

        List<SetLineHeuristics.SetAndNumber> pairs = SetLineHeuristics.pair(numbers, setCodes);
        boolean guided = guide != null;
        lookupExecutor.execute(() -> resolveReadings(lines, pairs, guided));

        scanListener.onScanActivity(outlines, snapshotPendingTitles());
    }

    /**
     * Guide-box reading: every line inside the outline (plus a little margin) goes into the
     * pass — the box already confines reads to one aimed card, so the whole card is searched
     * at once without the frame-wide hazards. Line-level rather than block-first, because a
     * block can start outside the box or lump the title with the mana cost. Every in-box line
     * lights the outline: the feedback is about where the reader is looking.
     */
    private void readGuidedBlock(Text.TextBlock block, int imageWidth, int imageHeight,
            float[] cornerViews, float[] guide,
            List<float[]> outlines, List<String> lines, List<String> numbers,
            List<String> setCodes) {
        float marginX = (guide[2] - guide[0]) * GUIDE_MARGIN_FRAC;
        float marginY = (guide[3] - guide[1]) * GUIDE_MARGIN_FRAC;
        float[] reach = {
                guide[0] - marginX, guide[1] - marginY, guide[2] + marginX, guide[3] + marginY,
        };
        for (Text.Line line : block.getLines()) {
            Rect box = line.getBoundingBox();
            if (box == null) {
                continue;
            }
            float[] viewQuad = viewQuadFor(box, imageWidth, imageHeight, cornerViews);
            if (!ScanGeometry.centerInBand(viewQuad, reach, 0f, 1f)) {
                continue;
            }
            lines.add(line.getText());
            String number = SetLineHeuristics.parseNumber(line.getText());
            if (number != null && !numbers.contains(number)) {
                numbers.add(number);
            }
            String setCode = SetLineHeuristics.parseSetCode(line.getText());
            if (setCode != null && !setCodes.contains(setCode)) {
                setCodes.add(setCode);
            }
            outlines.add(viewQuad);
        }
    }

    /**
     * One pass's whole-card resolution, on the lookup thread. Every line is matched against
     * the local catalog; each matched name costs one exact fetch, misses cost nothing. The
     * network fuzzy lookup survives only as a last resort — a guided pass where the whole box
     * matched nothing (heavy glare, a finger over the title), or a pass before the catalog
     * has ever loaded — and then fires once for the pass, not once per line.
     */
    private void resolveReadings(
            List<String> lines, List<SetLineHeuristics.SetAndNumber> pairs, boolean guided) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines) {
            String name = catalog.bestMatch(line);
            if (name != null) {
                names.add(name);
            }
        }
        for (String name : names) {
            if (guided) {
                rememberGuideName(name);
            }
            scheduleNameLookup(name);
        }

        if (names.isEmpty() && (guided || !catalog.isReady())) {
            for (String line : lines) {
                String title = TitleHeuristics.clean(line);
                if (title != null) {
                    scheduleFuzzyLookup(title, guided);
                    break;
                }
            }
        }

        for (SetLineHeuristics.SetAndNumber pair : pairs) {
            schedulePrintingLookup(pair, guided);
        }
    }

    /** An ML Kit bounding box as a view-space 4-corner polygon. */
    private static float[] viewQuadFor(
            Rect box, int imageWidth, int imageHeight, float[] cornerViews) {
        float[] imageBox = ScanGeometry.rotatedBoxToImage(
                new float[] {box.left, box.top, box.right, box.bottom},
                imageHeight, ROTATION_DEGREES);
        return ScanGeometry.imageQuadToView(
                boxCorners(imageBox), imageWidth, imageHeight, cornerViews);
    }

    /** A box {l, t, r, b} as the 4-corner polygon the overlay draws. */
    private static float[] boxCorners(float[] box) {
        return new float[] {
                box[0], box[1], box[2], box[1], box[2], box[3], box[0], box[3],
        };
    }

    private synchronized List<String> snapshotPendingTitles() {
        return new ArrayList<>(pendingDisplay.values());
    }

    /** Fetches details for a name the catalog confirmed — the one network call a card costs. */
    private void scheduleNameLookup(String name) {
        String key = "name:" + name.toLowerCase();
        synchronized (this) {
            if (rejected.contains(key) || matchedByName.containsKey(name.toLowerCase())
                    || !pending.add(key)) {
                return;
            }
            pendingDisplay.put(key, name);
        }
        lookupExecutor.execute(() -> lookUp(key, false, false,
                () -> ScryfallLookup.findByExactName(name)));
    }

    /** The last-resort network fuzzy lookup for a pass no catalog match could explain. */
    private void scheduleFuzzyLookup(String title, boolean guided) {
        String key = "title:" + title.toLowerCase();
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
            pendingDisplay.put(key, title);
        }
        lookupExecutor.execute(() -> lookUp(key, false, guided,
                () -> ScryfallLookup.findByFuzzyName(title)));
    }

    private void schedulePrintingLookup(SetLineHeuristics.SetAndNumber pair, boolean guided) {
        String key = "printing:" + pair.setCode + "/" + pair.collectorNumber;
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
            pendingDisplay.put(key, pair.setCode.toUpperCase() + " " + pair.collectorNumber);
        }
        lookupExecutor.execute(() -> lookUp(key, true, guided,
                () -> ScryfallLookup.bySetAndNumber(pair.setCode, pair.collectorNumber)));
    }

    private interface Lookup {
        ScryfallLookup.CardSummary run() throws IOException;
    }

    private void lookUp(String key, boolean exact, boolean guided, Lookup lookup) {
        try {
            ScryfallLookup.CardSummary card = lookup.run();
            if (card == null) {
                synchronized (this) {
                    rememberRejection(key);
                }
                return;
            }
            if (guided && !exact) {
                rememberGuideName(card.name);
            }
            if (guided && exact && !agreesWithGuideNames(card.name)) {
                // The collector line named a card the box's matched name does not corroborate —
                // a misread digit, most likely. Not remembered as rejected: once the right name
                // matches (or the stale one ages out), a later pass may retry this reading.
                Log.i(TAG, "Guide box: dropping " + key + " — " + card.name
                        + " disagrees with the aimed card's name");
                return;
            }
            List<ScryfallLookup.CardSummary> snapshot;
            synchronized (this) {
                snapshot = addMatch(card, exact);
            }
            if (snapshot != null) {
                listener.onCandidates(snapshot);
            }
        } catch (IOException e) {
            // Network trouble: forget the attempt so a later frame can retry this reading.
            Log.w(TAG, "Scryfall lookup failed for " + key, e);
        } catch (RuntimeException e) {
            // A malformed response must not wedge this reading in the pending set forever.
            Log.w(TAG, "Lookup failed unexpectedly for " + key, e);
            synchronized (this) {
                rememberRejection(key);
            }
        } finally {
            synchronized (this) {
                pending.remove(key);
                pendingDisplay.remove(key);
            }
            // Settled either way: the status line should stop saying this one is being checked.
            scanListener.onScanActivity(null, snapshotPendingTitles());
        }
    }

    /** Records a name the guide box matched; collector-line hits are checked against it. */
    private synchronized void rememberGuideName(String name) {
        guideNamesSeenAtMs.put(name.toLowerCase(), SystemClock.elapsedRealtime());
    }

    /** True when no box name is live (the collector line stands alone) or the name matches. */
    private synchronized boolean agreesWithGuideNames(String name) {
        long cutoff = SystemClock.elapsedRealtime() - GUIDE_NAME_TTL_MS;
        guideNamesSeenAtMs.values().removeIf(seenAt -> seenAt < cutoff);
        return guideNamesSeenAtMs.isEmpty() || guideNamesSeenAtMs.containsKey(name.toLowerCase());
    }

    /** Forgets a recognised card and refuses that name until the next {@link #rescan}. */
    synchronized void dismiss(String cardName) {
        String nameKey = cardName.toLowerCase();
        dismissedNames.add(nameKey);
        matchedByName.remove(nameKey);
    }

    /**
     * A deliberate fresh start: removed names are allowed back, rejected readings get another
     * chance, and the match memory clears so whatever the camera sees now is re-tried. Adopted
     * cards are untouched — this only reopens what earlier passes closed off.
     */
    synchronized void rescan() {
        dismissedNames.clear();
        matchedByName.clear();
        rejected.clear();
    }

    /** Records a hit; an exact printing displaces a name hit for the same card. */
    private List<ScryfallLookup.CardSummary> addMatch(
            ScryfallLookup.CardSummary card, boolean exact) {
        String nameKey = card.name.toLowerCase();
        if (dismissedNames.contains(nameKey)) {
            return null;
        }
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
