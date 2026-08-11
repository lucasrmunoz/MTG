package com.lucasmunoz.mtg.ar;

import android.graphics.Bitmap;
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
import java.nio.ByteBuffer;
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
 *
 * Guide-box passes do not OCR the raw frame. The luma plane is cropped to the aimed box,
 * contrast-stretched and upscaled first — so a bright background's auto-exposure crush or a
 * glare wash does not decide whether the text is readable — and read in both orientations,
 * so a card facing the other player identifies too.
 *
 * Guided readings also vote before they are believed: a matched name or collector line must
 * be reproduced by a second pass before its lookup fires. A lone misread digit names a wrong
 * printing that the name cross-check cannot catch on a basic land — any Island agrees with
 * "Island" — but the same misread rarely repeats, so it loses the vote while the true
 * reading confirms one pass (~350 ms) later.
 */
final class CardIdentifier {

    interface Listener {
        /** Called off the main thread each time the candidate list grows. */
        void onCandidates(List<ScryfallLookup.CardSummary> candidates);
    }

    interface GuideListener {
        /** Called on the main thread each time a guided pass reads text inside the box. */
        void onGuideRead();
    }

    interface DuplicateListener {
        /**
         * Called off the main thread when a guided pass re-reads the most recently scanned
         * card. Re-reads never re-match on their own; {@link #rescanMostRecent} is the
         * deliberate way to scan the same card twice.
         */
        void onDuplicateScan(String cardName);
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

    /** The aimed card upside down — how an opponent's card across the table reads. */
    private static final int FLIPPED_ROTATION_DEGREES = 270;

    /** Reads reach this far beyond the outline, forgiving a card that overflows it. */
    private static final float GUIDE_MARGIN_FRAC = 0.10f;

    /** Title glyphs sit near ML Kit's minimum at CPU-image resolution; doubling fixes that. */
    private static final int GUIDE_UPSCALE = 2;

    /** A guide crop smaller than this holds no readable card; the pass is skipped. */
    private static final int MIN_CROP_PX = 48;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ExecutorService lookupExecutor;
    private final CardNameCatalog catalog;
    private final Listener listener;
    private final GuideListener guideListener;
    private final DuplicateListener duplicateListener;

    /** Name key of the most recent recorded match; a guided re-read of it is a duplicate scan.
     *  Guarded by this. */
    private String lastScanNameKey;

    private volatile boolean recognizing;
    private long lastAttemptMs;

    /**
     * View-space {left, top, right, bottom} of the on-screen guide box, or null for ambient
     * scanning. While set, reading is confined to the box: the frame is cropped to it before
     * OCR, and every line the crop yields describes the one aimed card — never frame-wide.
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

    /**
     * Guided passes vote: a reading must be seen by this many passes (within the guide-name
     * TTL) before its lookup fires. At the ~350 ms guide cadence a steady card confirms one
     * pass later than before, and a misread that a single pass produced never gets a second
     * vote — it is dropped instead of adopted as the wrong printing.
     */
    private static final int GUIDE_AGREEING_PASSES = 2;

    private final GuideConsensus guideConsensus =
            new GuideConsensus(GUIDE_AGREEING_PASSES, GUIDE_NAME_TTL_MS);

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
     * on the table and replaces a name hit for the same card, which for a basic land would
     * otherwise float arbitrary artwork.
     */
    private final Map<String, Match> matchedByName = new LinkedHashMap<>();

    /** Names the user removed. Without this, the next lookup replays the match and the card
     *  rejoins within a frame — a removal silences the name until the next {@link #rescan}. */
    private final Set<String> dismissedNames = new HashSet<>();

    CardIdentifier(ExecutorService lookupExecutor, CardNameCatalog catalog, Listener listener,
            GuideListener guideListener, DuplicateListener duplicateListener) {
        this.lookupExecutor = lookupExecutor;
        this.catalog = catalog;
        this.listener = listener;
        this.guideListener = guideListener;
        this.duplicateListener = duplicateListener;
    }

    /** Confines scanning to a view-space box, or null to return to ambient frame-wide reads. */
    void setGuideBox(float[] viewBox) {
        this.guideBox = viewBox;
        guideConsensus.reset();
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
        // From here every outcome — read, skipped, degenerate crop — waits a full interval.
        lastAttemptMs = now;

        // Snapshot: the toggle must not switch modes between recognition and handling.
        float[] guide = guideBox;
        if (guide == null) {
            recognizing = true;
            InputImage input = InputImage.fromMediaImage(image, ROTATION_DEGREES);
            recognizer.process(input)
                    .addOnSuccessListener(this::handleAmbientText)
                    .addOnCompleteListener(task -> {
                        // The Image backs InputImage until processing completes; close it now.
                        image.close();
                        recognizing = false;
                    });
            return;
        }

        Bitmap crop = extractGuideCrop(frame, image, guide);
        if (crop == null) {
            return; // Not laid out over the image, or a stride this device never produces.
        }
        recognizing = true;
        readGuideCrop(crop);
    }

    /**
     * The guide region as an upscaled, contrast-stretched grayscale bitmap; always closes the
     * image — the copy outlives it, freeing the CPU-image budget before OCR even starts.
     */
    private Bitmap extractGuideCrop(Frame frame, Image image, float[] guide) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        // Where the sensor image lands on screen, captured now — the frame is only valid on
        // this thread during this update. Three corners pin down the affine display transform.
        float[] cornerViews = new float[6];
        frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                new float[] {0, 0, imageWidth, 0, 0, imageHeight},
                Coordinates2d.VIEW,
                cornerViews);

        byte[] luma;
        int rowStride;
        try {
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 1) {
                // YUV_420_888 guarantees a packed Y plane; anything else would corrupt the read.
                Log.w(TAG, "Skipping frame with Y-plane pixel stride " + plane.getPixelStride());
                return null;
            }
            ByteBuffer buffer = plane.getBuffer();
            luma = new byte[buffer.remaining()];
            buffer.get(luma);
            rowStride = plane.getRowStride();
        } finally {
            image.close();
        }

        float marginX = (guide[2] - guide[0]) * GUIDE_MARGIN_FRAC;
        float marginY = (guide[3] - guide[1]) * GUIDE_MARGIN_FRAC;
        float[] reach = {
                guide[0] - marginX, guide[1] - marginY, guide[2] + marginX, guide[3] + marginY,
        };
        int[] box = ScanGeometry.viewBoxToImageBox(reach, imageWidth, imageHeight, cornerViews);
        if (box == null
                || box[2] - box[0] < MIN_CROP_PX || box[3] - box[1] < MIN_CROP_PX) {
            return null;
        }

        int cropWidth = box[2] - box[0];
        int cropHeight = box[3] - box[1];
        byte[] stretched =
                LumaOps.cropStretched(luma, rowStride, box[0], box[1], cropWidth, cropHeight);
        int[] pixels = new int[cropWidth * cropHeight];
        for (int i = 0; i < pixels.length; i++) {
            int value = stretched[i] & 0xFF;
            pixels[i] = 0xFF000000 | (value << 16) | (value << 8) | value;
        }
        Bitmap gray = Bitmap.createBitmap(pixels, cropWidth, cropHeight, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(
                gray, cropWidth * GUIDE_UPSCALE, cropHeight * GUIDE_UPSCALE, true);
    }

    /**
     * OCRs the crop upright and then upside down — cards face their controller, so the one in
     * the box is as likely an opponent's as the user's own. Both reads pool into one pass;
     * listeners run on the main thread, so the shared list needs no lock.
     */
    private void readGuideCrop(Bitmap crop) {
        List<String> lines = new ArrayList<>();
        InputImage upright = InputImage.fromBitmap(crop, ROTATION_DEGREES);
        InputImage flipped = InputImage.fromBitmap(crop, FLIPPED_ROTATION_DEGREES);
        recognizer.process(upright)
                .addOnSuccessListener(text -> collectLines(text, lines))
                .addOnCompleteListener(first -> recognizer.process(flipped)
                        .addOnSuccessListener(text -> collectLines(text, lines))
                        .addOnCompleteListener(second -> {
                            crop.recycle();
                            finishGuidedPass(lines);
                            recognizing = false;
                        }));
    }

    private static void collectLines(Text text, List<String> lines) {
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lines.add(line.getText());
            }
        }
    }

    /** The whole crop is the one aimed card, so every line it yielded joins the pass. */
    private void finishGuidedPass(List<String> lines) {
        if (!lines.isEmpty()) {
            guideListener.onGuideRead();
        }
        List<String> numbers = new ArrayList<>();
        List<String> setCodes = new ArrayList<>();
        for (String line : lines) {
            String number = SetLineHeuristics.parseNumber(line);
            if (number != null && !numbers.contains(number)) {
                numbers.add(number);
            }
            String setCode = SetLineHeuristics.parseSetCode(line);
            if (setCode != null && !setCodes.contains(setCode)) {
                setCodes.add(setCode);
            }
        }
        List<SetLineHeuristics.SetAndNumber> pairs = SetLineHeuristics.pair(numbers, setCodes);
        lookupExecutor.execute(() -> resolveReadings(lines, pairs, true));
    }

    /**
     * Ambient reading: collects the frame's readings and hands them to the resolver on the
     * lookup thread — catalog matching over tens of thousands of names is milliseconds of
     * work, but not main-thread milliseconds.
     */
    private void handleAmbientText(Text text) {
        List<String> lines = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        List<String> setCodes = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            if (block.getLines().isEmpty()) {
                continue;
            }
            // A card's title is the first line of its own text block; deeper lines are type
            // lines and rules text, which name matching would have to wade through for nothing.
            String title = TitleHeuristics.clean(block.getLines().get(0).getText());
            if (title != null) {
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
        lookupExecutor.execute(() -> resolveReadings(lines, pairs, false));
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
        long now = SystemClock.elapsedRealtime();
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines) {
            String name = catalog.bestMatch(line);
            if (name != null) {
                names.add(name);
            }
        }
        for (String name : names) {
            if (guided) {
                // Corroboration wants the freshest names even while the vote is still open.
                rememberGuideName(name);
                if (!guideConsensus.confirm("name:" + name.toLowerCase(), now)) {
                    continue;
                }
            }
            scheduleNameLookup(name, guided);
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
            if (guided && !guideConsensus.confirm(
                    "printing:" + pair.setCode + "/" + pair.collectorNumber, now)) {
                continue;
            }
            schedulePrintingLookup(pair, guided);
        }
    }

    /** Fetches details for a name the catalog confirmed — the one network call a card costs. */
    private void scheduleNameLookup(String name, boolean guided) {
        String nameKey = name.toLowerCase();
        String key = "name:" + nameKey;
        String duplicateOfLastScan = null;
        boolean schedule = false;
        synchronized (this) {
            Match existing = matchedByName.get(nameKey);
            if (existing != null) {
                // Aiming at the card just scanned: never silently re-match — the listener
                // invites the deliberate tap that reopens it instead.
                if (guided && nameKey.equals(lastScanNameKey)) {
                    duplicateOfLastScan = existing.card.name;
                }
            } else if (!rejected.contains(key) && pending.add(key)) {
                schedule = true;
            }
        }
        if (duplicateOfLastScan != null) {
            duplicateListener.onDuplicateScan(duplicateOfLastScan);
        }
        if (schedule) {
            lookupExecutor.execute(() -> lookUp(key, false, false,
                    () -> ScryfallLookup.findByExactName(name)));
        }
    }

    /** The last-resort network fuzzy lookup for a pass no catalog match could explain. */
    private void scheduleFuzzyLookup(String title, boolean guided) {
        String key = "title:" + title.toLowerCase();
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
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
            }
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
        if (nameKey.equals(lastScanNameKey)) {
            lastScanNameKey = null;
        }
    }

    /**
     * Reopens the most recent scan so the next pass may confirm it again — the deliberate
     * "tap to scan this card again". Returns the reopened card's name, or null when there is
     * no scan to reopen.
     */
    synchronized String rescanMostRecent() {
        if (lastScanNameKey == null) {
            return null;
        }
        Match removed = matchedByName.remove(lastScanNameKey);
        return removed == null ? null : removed.card.name;
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
        lastScanNameKey = null;
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
        lastScanNameKey = nameKey;

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
