package com.lucasmunoz.mtg.ar;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads Magic cards off camera frames and turns them into confirmed candidates.
 *
 * Identification is a three-gate pipeline. Contour detection finds card-shaped quads, so only
 * text sitting on a physical card rectangle is ever considered. Each quad is perspective-warped
 * flat and OCR'd: the title band names the card and the collector band names the exact
 * printing, paired within that one card — never across the frame. Scryfall then confirms the
 * reading, and the quad's artwork is compared against the printing's scan by perceptual hash,
 * so a misread that resolves to a real-but-wrong card fails the artwork gate and never joins.
 */
final class CardIdentifier {

    interface Listener {
        /** Called off the main thread each time the candidate list grows. */
        void onCandidates(List<ScryfallLookup.CardSummary> candidates);
    }

    interface ScanListener {
        /**
         * Live scanning feedback, called off the main thread. After each detection pass,
         * cardOutlines holds one view-space 4-corner polygon (8 floats) per card-shaped quad
         * in frame; when a lookup settles the call carries null outlines (the ones on screen
         * are still current) with the refreshed pending list.
         */
        void onScanActivity(List<float[]> cardOutlines, List<String> pendingTitles);
    }

    private static final String TAG = "CardIdentifier";

    /** How often to scan a frame; more brings no benefit at hand-held steadiness. */
    private static final long ATTEMPT_INTERVAL_MS = 700;
    /** The flattened card OCR and hashing work on; close to the physical 63:88. */
    private static final int WARP_WIDTH = 384;
    private static final int WARP_HEIGHT = 536;
    /** The title lives in the card's top band… */
    private static final float TITLE_BAND = 0.17f;
    /** …and the collector line in the bottom one, both as fractions of card height. */
    private static final float COLLECTOR_BAND = 0.84f;
    /** How many gradient bits two artworks may disagree on and still be the same picture. */
    private static final int MATCH_DISTANCE = 22;
    /** A fuzzy name hit verifies against at most this many art versions before giving up. */
    private static final int MAX_VERSIONS_TO_VERIFY = 8;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ExecutorService lookupExecutor;
    private final Listener listener;
    private final ScanListener scanListener;

    private volatile boolean recognizing;
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

    /** Readings Scryfall (or the artwork gate) already rejected, so they are not re-queried. */
    private final Set<String> rejected = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    /** What each pending lookup is about, human-readable, for the "checking …" status line. */
    private final Map<String, String> pendingDisplay = new LinkedHashMap<>();
    /**
     * Candidates keyed by card name. A collector-line or artwork-verified hit names the exact
     * printing on the table and replaces a fuzzy name hit for the same card.
     */
    private final Map<String, Match> matchedByName = new LinkedHashMap<>();

    /** Names the user removed. Without this, the next lookup replays the match and the card
     *  rejoins within a frame — a removal silences the name until the next {@link #rescan}. */
    private final Set<String> dismissedNames = new HashSet<>();

    /** Artwork hashes by printing id, so each scan downloads at most once. */
    private final Map<String, Long> printingHashes = new HashMap<>();

    CardIdentifier(ExecutorService lookupExecutor, Listener listener, ScanListener scanListener) {
        this.lookupExecutor = lookupExecutor;
        this.listener = listener;
        this.scanListener = scanListener;
    }

    /** Called from the GL thread once per rendered frame; does nothing most of the time. */
    void maybeIdentify(Frame frame) {
        long now = SystemClock.elapsedRealtime();
        if (recognizing || now - lastAttemptMs < ATTEMPT_INTERVAL_MS) {
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

        // Copy the Y plane — the grayscale picture — so the camera image can be closed at
        // once; detection, warping and OCR then run entirely off the GL thread.
        Image.Plane yPlane = image.getPlanes()[0];
        if (yPlane.getPixelStride() != 1) {
            // The YUV_420_888 spec fixes the Y pixel stride at 1; anything else is a device
            // quirk this pipeline does not support.
            Log.e(TAG, "Unsupported Y-plane pixel stride: " + yPlane.getPixelStride());
            image.close();
            return;
        }
        ByteBuffer buffer = yPlane.getBuffer();
        byte[] gray = new byte[buffer.remaining()];
        buffer.get(gray);
        int rowStride = yPlane.getRowStride();
        image.close();

        lastAttemptMs = now;
        recognizing = true;
        lookupExecutor.execute(() -> {
            try {
                processFrame(gray, rowStride, imageWidth, imageHeight, cornerViews);
            } finally {
                recognizing = false;
            }
        });
    }

    /** Detects card quads, publishes their outlines, then reads each one. */
    private void processFrame(
            byte[] gray, int rowStride, int width, int height, float[] cornerViews) {
        List<CardQuadDetector.Quad> quads = CardQuadDetector.detect(gray, rowStride, width, height);

        List<float[]> outlines = new ArrayList<>(quads.size());
        for (CardQuadDetector.Quad quad : quads) {
            outlines.add(ScanGeometry.imageQuadToView(quad.corners, width, height, cornerViews));
        }
        scanListener.onScanActivity(outlines, snapshotPendingTitles());

        for (CardQuadDetector.Quad quad : quads) {
            readQuad(CardQuadDetector.warp(
                    gray, rowStride, width, height, quad, WARP_WIDTH, WARP_HEIGHT));
        }
    }

    /** OCRs one flattened card and schedules lookups for whatever its bands yield. */
    private void readQuad(Bitmap warped) {
        Text text = recognizeSync(warped);
        if (text == null) {
            return;
        }
        String title = titleFrom(text, warped.getHeight());
        SetLineHeuristics.SetAndNumber collectorLine = collectorFrom(text, warped.getHeight());
        if (title == null && collectorLine == null) {
            // Perhaps the card faces its owner across the table: read it upside down.
            warped = rotate180(warped);
            text = recognizeSync(warped);
            if (text == null) {
                return;
            }
            title = titleFrom(text, warped.getHeight());
            collectorLine = collectorFrom(text, warped.getHeight());
        }
        if (title == null && collectorLine == null) {
            return;
        }

        long quadHash = DHash.of(warped);
        if (collectorLine != null) {
            schedulePrintingLookup(collectorLine, quadHash);
        }
        if (title != null) {
            scheduleTitleLookup(title, quadHash);
        }
    }

    /** ML Kit on the executor thread; blocking here is what keeps the pipeline ordered. */
    private Text recognizeSync(Bitmap bitmap) {
        try {
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            Log.w(TAG, "OCR failed on a flattened card.", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The topmost plausible title line within the card's title band, or null. */
    private static String titleFrom(Text text, int height) {
        String best = null;
        int bestTop = Integer.MAX_VALUE;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || box.centerY() > height * TITLE_BAND) {
                    continue;
                }
                String title = TitleHeuristics.clean(line.getText());
                if (title != null && box.top < bestTop) {
                    bestTop = box.top;
                    best = title;
                }
            }
        }
        return best;
    }

    /** The collector-line reading from the card's bottom band, or null. */
    private static SetLineHeuristics.SetAndNumber collectorFrom(Text text, int height) {
        List<String> numbers = new ArrayList<>();
        List<String> setCodes = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || box.centerY() < height * COLLECTOR_BAND) {
                    continue;
                }
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
        return pairs.isEmpty() ? null : pairs.get(0);
    }

    private static Bitmap rotate180(Bitmap bitmap) {
        Matrix matrix = new Matrix();
        matrix.postRotate(180);
        return Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private synchronized List<String> snapshotPendingTitles() {
        return new ArrayList<>(pendingDisplay.values());
    }

    private void scheduleTitleLookup(String title, long quadHash) {
        String key = "title:" + title.toLowerCase();
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
            pendingDisplay.put(key, title);
        }
        lookupExecutor.execute(() -> lookUp(key, false, quadHash,
                () -> ScryfallLookup.findByFuzzyName(title)));
    }

    private void schedulePrintingLookup(SetLineHeuristics.SetAndNumber pair, long quadHash) {
        String key = "printing:" + pair.setCode + "/" + pair.collectorNumber;
        synchronized (this) {
            if (rejected.contains(key) || !pending.add(key)) {
                return;
            }
            pendingDisplay.put(key, pair.setCode.toUpperCase() + " " + pair.collectorNumber);
        }
        lookupExecutor.execute(() -> lookUp(key, true, quadHash,
                () -> ScryfallLookup.bySetAndNumber(pair.setCode, pair.collectorNumber)));
    }

    private interface Lookup {
        ScryfallLookup.CardSummary run() throws IOException;
    }

    private void lookUp(String key, boolean exact, long quadHash, Lookup lookup) {
        try {
            ScryfallLookup.CardSummary card = lookup.run();
            List<ScryfallLookup.CardSummary> snapshot = null;
            boolean artworkConfirmed = false;
            if (card != null) {
                ScryfallLookup.CardSummary verified = verifyArtwork(card, exact, quadHash);
                artworkConfirmed = verified != null && verified != card;
                card = verified;
            }
            synchronized (this) {
                pending.remove(key);
                pendingDisplay.remove(key);
                if (card == null) {
                    rememberRejection(key);
                } else {
                    snapshot = addMatch(card, exact || artworkConfirmed);
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
                pendingDisplay.remove(key);
            }
        }
        // Settled either way: the status line should stop saying this one is being checked.
        scanListener.onScanActivity(null, snapshotPendingTitles());
    }

    /**
     * The artwork gate: compares what the camera saw against the proposed printing's scan.
     * Returns the printing whose artwork matches — the candidate itself, or for a fuzzy name
     * hit possibly another art version, which is then the exact physical copy on the table.
     * Null means nothing matched: an OCR misread that resolved to the wrong card. A failed
     * download fails open — the quad and Scryfall gates have already passed.
     */
    private ScryfallLookup.CardSummary verifyArtwork(
            ScryfallLookup.CardSummary card, boolean exact, long quadHash) {
        Integer distance = artworkDistance(card.id, card.imageUrl, quadHash);
        if (distance == null || distance <= MATCH_DISTANCE) {
            return card;
        }
        if (!exact) {
            try {
                int examined = 0;
                for (ScryfallLookup.CardSummary version : ScryfallLookup.artVersions(card.name)) {
                    if (++examined > MAX_VERSIONS_TO_VERIFY) {
                        break;
                    }
                    Integer versionDistance =
                            artworkDistance(version.id, version.imageUrl, quadHash);
                    if (versionDistance != null && versionDistance <= MATCH_DISTANCE) {
                        return version;
                    }
                }
            } catch (IOException e) {
                return card; // Could not list versions: fail open.
            }
        }
        Log.d(TAG, "Artwork gate rejected " + card.name + " at distance " + distance);
        return null;
    }

    /** Hash distance to a printing's scan, or null when the scan cannot be fetched. */
    private Integer artworkDistance(String printingId, String imageUrl, long quadHash) {
        if (imageUrl == null) {
            return null;
        }
        Long hash;
        synchronized (printingHashes) {
            hash = printingHashes.get(printingId);
        }
        if (hash == null) {
            try {
                hash = DHash.of(ImageFetcher.fetch(imageUrl));
            } catch (IOException e) {
                return null;
            }
            synchronized (printingHashes) {
                printingHashes.put(printingId, hash);
            }
        }
        return DHash.distance(hash, quadHash);
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

    /** Records a hit; an exact printing displaces a fuzzy hit for the same card name. */
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
