package com.lucasmunoz.mtg.ar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.ImageInsufficientQualityException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.lucasmunoz.mtg.R;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The AR screen: every card the camera confirms shows up at once, floating over its physical
 * copy, each carrying its own counters.
 *
 * Cards join automatically — the scanner reads titles and collector lines continuously, and every
 * Scryfall-confirmed card gets its printings registered as reference images; the moment tracking
 * locks, the card appears. Recognition is reference-image tracking only, never "that shape looks
 * like a card". Cards whose scans cannot be found (sleeve glare, OCR misreads) wait as chips at
 * the bottom: tap the chip, then tap a surface, to place one by hand. Tapping a card focuses it
 * for the counter panel; tapping the focused card lays it onto the physical card and back.
 * Counters persist per printing and reattach whenever that printing is recognised again.
 */
public final class ArCardActivity extends Activity implements CardOverlayView.Listener {

    public static final String EXTRA_CARD_ID = "cardId";
    public static final String EXTRA_CARD_NAME = "cardName";
    public static final String EXTRA_IMAGE_URL = "imageUrl";
    /** JSON array of {id, imageUrl} — the printings to register as reference images. */
    public static final String EXTRA_PRINTINGS = "printings";

    private static final String TAG = "ArCardActivity";

    /** A Magic card is 63mm x 88mm; ARCore wants the physical width of every reference image. */
    private static final float CARD_WIDTH_M = 0.063f;
    private static final float CARD_HEIGHT_M = 0.088f;

    private static final int CAMERA_PERMISSION_CODE = 41;
    private static final int MAX_REFERENCE_IMAGES_PER_CARD = 12;

    /** One card on (or headed for) the table. Tracking fields are guarded by sessionLock. */
    private static final class ActiveCard {
        final String key;
        final String name;
        volatile String printingId;
        final Map<String, String> printingUrls = new ConcurrentHashMap<>();
        volatile boolean located;
        volatile float halfWidthM = CARD_WIDTH_M / 2f;
        volatile float halfHeightM = CARD_HEIGHT_M / 2f;
        AugmentedImage trackedImage;
        Anchor anchor;

        ActiveCard(String key, String name, String imageUrl) {
            this.key = key;
            this.name = name;
            this.printingId = key;
            printingUrls.put(key, imageUrl);
        }
    }

    private GLSurfaceView surfaceView;
    private CardOverlayView overlay;
    private TextView statusText;
    private LinearLayout chipRow;
    private TextView taxLabel;
    private LinearLayout candidateRow;
    private View candidatesScroll;
    private View panel;

    private final Object sessionLock = new Object();
    private Session session;
    /** True when reference images changed since the session last configured. Guarded by lock. */
    private boolean databaseDirty;
    private boolean installRequested;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

    private volatile boolean viewportChanged;
    private volatile int viewportWidth;
    private volatile int viewportHeight;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private CardIdentifier identifier;
    private CounterStore store;
    private boolean saveFailureReported;

    private final Map<String, ActiveCard> cardsByKey = new ConcurrentHashMap<>();
    private final List<ActiveCard> cardOrder = new CopyOnWriteArrayList<>();
    /** Which card each registered reference image belongs to, by augmented-image name. */
    private final Map<String, ActiveCard> cardByPrinting = new ConcurrentHashMap<>();
    private final Map<String, Bitmap> referenceBitmaps = new ConcurrentHashMap<>();

    private volatile String focusedKey;
    /** A chip was tapped: the next surface tap places this card. */
    private volatile String pendingPlacementKey;
    private volatile float pendingTapX = -1f;
    private volatile float pendingTapY = -1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_card);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        statusText = findViewById(R.id.ar_status);
        chipRow = findViewById(R.id.ar_chip_row);
        taxLabel = findViewById(R.id.ar_tax_label);
        candidateRow = findViewById(R.id.ar_candidate_row);
        candidatesScroll = findViewById(R.id.ar_candidates);
        panel = findViewById(R.id.ar_panel);
        overlay = findViewById(R.id.ar_overlay);
        overlay.setListener(this);

        surfaceView = findViewById(R.id.ar_surface);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(new SceneRenderer());
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        applySystemBarInsets();
        store = new CounterStore(getFilesDir());
        wireCounterControls();
        panel.setVisibility(View.GONE);
        statusText.setText(R.string.ar_status_scan);

        // The scanner always runs: every confirmed card joins the scene by itself.
        identifier = new CardIdentifier(executor, this::onCandidatesRecognized);

        addCardFromIntent();
    }

    /** A card opened from search joins immediately, with its art versions sent by the web side. */
    private void addCardFromIntent() {
        String cardId = getIntent().getStringExtra(EXTRA_CARD_ID);
        String cardName = getIntent().getStringExtra(EXTRA_CARD_NAME);
        String imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        if (cardId == null || cardName == null || imageUrl == null) {
            return; // Scan mode: the camera decides which cards exist.
        }

        ActiveCard card = new ActiveCard(cardId, cardName, imageUrl);
        String printingsJson = getIntent().getStringExtra(EXTRA_PRINTINGS);
        if (printingsJson != null) {
            try {
                JSONArray printings = new JSONArray(printingsJson);
                for (int i = 0; i < printings.length()
                        && card.printingUrls.size() < MAX_REFERENCE_IMAGES_PER_CARD; i++) {
                    JSONObject printing = printings.getJSONObject(i);
                    card.printingUrls.putIfAbsent(
                            printing.getString("id"), printing.getString("imageUrl"));
                }
            } catch (JSONException e) {
                Log.w(TAG, "Ignoring malformed printings payload.", e);
            }
        }
        adoptCard(card, false);
    }

    /**
     * The activity draws edge-to-edge (Android 15 enforces it), so the camera view stays
     * full-bleed while the touchable chrome moves inside the system bars.
     */
    private void applySystemBarInsets() {
        View close = findViewById(R.id.ar_close);
        View bottom = findViewById(R.id.ar_bottom);
        float density = getResources().getDisplayMetrics().density;

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ar_root), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

            FrameLayout.LayoutParams closeParams =
                    (FrameLayout.LayoutParams) close.getLayoutParams();
            closeParams.topMargin = bars.top + (int) (8 * density);
            close.setLayoutParams(closeParams);

            FrameLayout.LayoutParams statusParams =
                    (FrameLayout.LayoutParams) statusText.getLayoutParams();
            statusParams.topMargin = bars.top + (int) (8 * density);
            statusText.setLayoutParams(statusParams);

            bottom.setPadding(bars.left, 0, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ------------------------------------------------------------------------------ card joining

    /** Every card the scanner has confirmed so far; new ones join the scene automatically. */
    private void onCandidatesRecognized(List<ScryfallLookup.CardSummary> candidates) {
        for (ScryfallLookup.CardSummary candidate : candidates) {
            ActiveCard existing = findCardByName(candidate.name);
            if (existing == null) {
                runOnUiThread(() -> adoptCard(
                        new ActiveCard(candidate.id, candidate.name, candidate.imageUrl), true));
            } else if (existing.printingUrls.putIfAbsent(candidate.id, candidate.imageUrl)
                    == null) {
                // A more precise reading — usually the collector line — named another printing
                // of a card already here. That printing is almost certainly the physical copy,
                // so it joins the reference images, and the counters key onto it until tracking
                // settles the question.
                cardByPrinting.put(candidate.id, existing);
                if (!existing.located) {
                    existing.printingId = candidate.id;
                }
                executor.execute(() -> downloadImages(existing));
            }
        }
    }

    private ActiveCard findCardByName(String name) {
        for (ActiveCard card : cardOrder) {
            if (card.name.equalsIgnoreCase(name)) {
                return card;
            }
        }
        return null;
    }

    /** Registers a card and starts fetching its art; it appears once tracking locks on. */
    private void adoptCard(ActiveCard card, boolean fetchArtVersions) {
        if (cardsByKey.putIfAbsent(card.key, card) != null) {
            return;
        }
        cardOrder.add(card);
        for (String printingId : card.printingUrls.keySet()) {
            cardByPrinting.put(printingId, card);
        }
        overlay.upsertCard(card.key, null);
        pushCounterChips(card);
        refreshPendingChips();

        executor.execute(() -> {
            if (fetchArtVersions) {
                try {
                    for (ScryfallLookup.CardSummary version
                            : ScryfallLookup.artVersions(card.name)) {
                        if (card.printingUrls.size() >= MAX_REFERENCE_IMAGES_PER_CARD) {
                            break;
                        }
                        if (card.printingUrls.putIfAbsent(version.id, version.imageUrl) == null) {
                            cardByPrinting.put(version.id, card);
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "No art versions; tracking only the identified printing.", e);
                }
            }
            downloadImages(card);
        });
    }

    /** Downloads any missing reference scans for a card, then re-registers the database. */
    private void downloadImages(ActiveCard card) {
        for (Map.Entry<String, String> entry : card.printingUrls.entrySet()) {
            if (referenceBitmaps.containsKey(entry.getKey())) {
                continue;
            }
            try {
                referenceBitmaps.put(entry.getKey(), ImageFetcher.fetch(entry.getValue()));
            } catch (IOException e) {
                // One printing failing to download only means that copy will not be recognised.
                Log.w(TAG, "Skipping reference image for " + entry.getKey(), e);
            }
        }

        // Until tracking picks the real printing, display the best guess: the printing the
        // counters currently key on.
        Bitmap display = referenceBitmaps.get(card.printingId);
        if (display == null) {
            display = referenceBitmaps.get(card.key);
        }
        if (display != null) {
            overlay.upsertCard(card.key, display);
        } else {
            Log.w(TAG, "No scan could be downloaded for " + card.name);
        }

        synchronized (sessionLock) {
            databaseDirty = true;
        }
        executor.execute(this::reconfigureDatabaseIfDirty);
    }

    /**
     * Rebuilds the reference-image database from every card's downloaded scans. Runs on the
     * executor: feature extraction takes tens of milliseconds per image. Cards already tracking
     * keep their place — the pose moves onto a world anchor before the session reconfigures,
     * because a database swap stops all current image tracking until re-detection.
     */
    private void reconfigureDatabaseIfDirty() {
        synchronized (sessionLock) {
            if (session == null || !databaseDirty || referenceBitmaps.isEmpty()) {
                return;
            }
            databaseDirty = false;

            AugmentedImageDatabase database = new AugmentedImageDatabase(session);
            int registered = 0;
            for (Map.Entry<String, Bitmap> entry : referenceBitmaps.entrySet()) {
                try {
                    database.addImage(entry.getKey(), entry.getValue(), CARD_WIDTH_M);
                    registered++;
                } catch (ImageInsufficientQualityException e) {
                    // A low-detail scan (full-art lands, mostly) cannot be tracked; the card can
                    // still be placed manually.
                    Log.w(TAG, "Not enough features to track printing " + entry.getKey());
                }
            }
            if (registered == 0) {
                return;
            }

            for (ActiveCard card : cardOrder) {
                if (card.trackedImage != null) {
                    if (card.anchor != null) {
                        card.anchor.detach();
                    }
                    card.anchor = session.createAnchor(card.trackedImage.getCenterPose());
                    card.trackedImage = null;
                }
            }
            session.configure(buildConfig(database));
        }
    }

    private Config buildConfig(AugmentedImageDatabase database) {
        Config config = new Config(session);
        config.setFocusMode(Config.FocusMode.AUTO);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
        config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
        if (database != null) {
            config.setAugmentedImageDatabase(database);
        }
        return config;
    }

    // -------------------------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureSession()) {
            return;
        }

        try {
            synchronized (sessionLock) {
                session.resume();
            }
        } catch (CameraNotAvailableException e) {
            Toast.makeText(this, R.string.ar_camera_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        surfaceView.onResume();
    }

    /** True when an AR session exists and is ready to resume. */
    private boolean ensureSession() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] {android.Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return false;
        }

        synchronized (sessionLock) {
            if (session != null) {
                return true;
            }
            try {
                ArCoreApk.InstallStatus status =
                        ArCoreApk.getInstance().requestInstall(this, !installRequested);
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    // Play Services for AR is being installed; this activity resumes afterwards.
                    installRequested = true;
                    return false;
                }
                session = new Session(this);
                session.configure(buildConfig(null));
                databaseDirty = !referenceBitmaps.isEmpty();
            } catch (UnavailableUserDeclinedInstallationException e) {
                Toast.makeText(this, R.string.ar_install_declined, Toast.LENGTH_LONG).show();
                finish();
                return false;
            } catch (UnavailableException e) {
                Log.w(TAG, "AR is unavailable on this device.", e);
                Toast.makeText(this, R.string.ar_unavailable, Toast.LENGTH_LONG).show();
                finish();
                return false;
            }
        }

        executor.execute(this::reconfigureDatabaseIfDirty);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CAMERA_PERMISSION_CODE
                && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.ar_camera_denied, Toast.LENGTH_LONG).show();
            finish();
        }
        // On grant, the interrupted onResume runs again and ensureSession succeeds.
    }

    @Override
    protected void onPause() {
        super.onPause();
        surfaceView.onPause();
        synchronized (sessionLock) {
            if (session != null) {
                session.pause();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (identifier != null) {
            identifier.close();
        }
        executor.shutdown();
        synchronized (sessionLock) {
            if (session != null) {
                session.close();
                session = null;
            }
        }
    }

    // ------------------------------------------------------------------ CardOverlayView.Listener

    @Override
    public void onFocusChanged(String key) {
        focusedKey = key;
        runOnUiThread(() -> {
            panel.setVisibility(View.VISIBLE);
            refreshCounterUi();
            updateStatusLine();
        });
    }

    @Override
    public void onTapEmpty(float x, float y) {
        // Placement needs a hit test against the AR frame, which only the GL thread can run.
        pendingTapX = x;
        pendingTapY = y;
    }

    // -------------------------------------------------------------------- pending-placement chips

    /** Cards identified but not yet located; a chip tap arms manual placement for that card. */
    private void refreshPendingChips() {
        runOnUiThread(() -> {
            candidateRow.removeAllViews();
            int shown = 0;
            for (ActiveCard card : cardOrder) {
                if (card.located) {
                    continue;
                }
                Button chip = new Button(this);
                chip.setText(card.name);
                chip.setAllCaps(false);
                chip.setOnClickListener(v -> {
                    pendingPlacementKey = card.key;
                    updateStatusLine();
                });
                candidateRow.addView(chip);
                shown++;
            }
            candidatesScroll.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        });
    }

    private void updateStatusLine() {
        String pendingKey = pendingPlacementKey;
        if (pendingKey != null) {
            ActiveCard pending = cardsByKey.get(pendingKey);
            if (pending != null) {
                statusText.setText(getString(R.string.ar_status_place, pending.name));
                return;
            }
        }
        String focused = focusedKey;
        ActiveCard card = focused == null ? null : cardsByKey.get(focused);
        statusText.setText(card == null
                ? getString(R.string.ar_status_scan)
                : getString(R.string.ar_status_tracking, card.name));
    }

    // ----------------------------------------------------------------------------- counter panel

    private CardCounters focusedCounters() {
        String key = focusedKey;
        ActiveCard card = key == null ? null : cardsByKey.get(key);
        return card == null ? null : store.get(card.printingId);
    }

    private void wireCounterControls() {
        findViewById(R.id.ar_close).setOnClickListener(v -> finish());
        wireStatButton(R.id.ar_stat_pp, 1, 1);
        wireStatButton(R.id.ar_stat_mm, -1, -1);
        wireStatButton(R.id.ar_stat_pm, 1, -1);
        wireStatButton(R.id.ar_stat_mp, -1, 1);
        findViewById(R.id.ar_stat_custom).setOnClickListener(v -> showCustomStatDialog());
        findViewById(R.id.ar_keyword).setOnClickListener(v -> showKeywordDialog());
        findViewById(R.id.ar_tax_minus).setOnClickListener(v -> adjustCommanderCasts(-1));
        findViewById(R.id.ar_tax_plus).setOnClickListener(v -> adjustCommanderCasts(1));
    }

    private void wireStatButton(int viewId, int power, int toughness) {
        findViewById(viewId).setOnClickListener(v -> {
            CardCounters counters = focusedCounters();
            if (counters == null) {
                return; // No card focused yet; the panel is hidden anyway.
            }
            counters.addStat(power, toughness);
            persistAndRefresh();
        });
    }

    private void adjustCommanderCasts(int delta) {
        CardCounters counters = focusedCounters();
        if (counters == null) {
            return;
        }
        counters.commanderCasts = Math.max(0, counters.commanderCasts + delta);
        persistAndRefresh();
    }

    private void showCustomStatDialog() {
        NumberPicker power = buildStatPicker();
        NumberPicker toughness = buildStatPicker();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.addView(power);
        row.addView(toughness);

        new AlertDialog.Builder(this)
                .setTitle(R.string.ar_custom_stat_title)
                .setView(row)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    CardCounters counters = focusedCounters();
                    if (counters == null) {
                        return;
                    }
                    counters.addStat(power.getValue() - 20, toughness.getValue() - 20);
                    persistAndRefresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Picker for -20..+20; NumberPicker only counts from zero, so values are offset by 20. */
    private NumberPicker buildStatPicker() {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(40);
        String[] labels = new String[41];
        for (int i = 0; i <= 40; i++) {
            int value = i - 20;
            labels[i] = value > 0 ? "+" + value : String.valueOf(value);
        }
        picker.setDisplayedValues(labels);
        picker.setValue(21); // +1 by default.
        return picker;
    }

    private void showKeywordDialog() {
        String[] presets = getResources().getStringArray(R.array.ar_keyword_presets);
        String[] options = new String[presets.length + 1];
        System.arraycopy(presets, 0, options, 0, presets.length);
        options[presets.length] = getString(R.string.ar_keyword_custom);

        new AlertDialog.Builder(this)
                .setTitle(R.string.ar_keyword_title)
                .setItems(options, (dialog, which) -> {
                    CardCounters counters = focusedCounters();
                    if (counters == null) {
                        return;
                    }
                    if (which < presets.length) {
                        counters.addKeyword(presets[which]);
                        persistAndRefresh();
                    } else {
                        showCustomKeywordDialog();
                    }
                })
                .show();
    }

    private void showCustomKeywordDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.ar_keyword_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ar_keyword_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    CardCounters counters = focusedCounters();
                    if (counters == null) {
                        return;
                    }
                    counters.addKeyword(input.getText().toString());
                    persistAndRefresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void persistAndRefresh() {
        try {
            store.save();
        } catch (IOException e) {
            Log.w(TAG, "Could not persist counters.", e);
            if (!saveFailureReported) {
                saveFailureReported = true;
                Toast.makeText(this, R.string.ar_save_failed, Toast.LENGTH_LONG).show();
            }
        }
        refreshCounterUi();
    }

    /** Rebuilds the focused card's tappable chip row and re-stamps its overlay chips. */
    private void refreshCounterUi() {
        CardCounters counters = focusedCounters();
        chipRow.removeAllViews();
        if (counters == null) {
            return;
        }

        for (String keyword : new ArrayList<>(counters.keywords)) {
            addChip(keyword, () -> counters.removeKeyword(keyword));
        }
        for (CardCounters.StatCounter stat : new ArrayList<>(counters.stats)) {
            String label = stat.count == 1 ? stat.label() : stat.label() + " ×" + stat.count;
            addChip(label, () -> counters.removeStat(stat.power, stat.toughness));
        }
        taxLabel.setText(getString(R.string.ar_tax_chip, counters.commanderTax()));

        ActiveCard card = focusedKey == null ? null : cardsByKey.get(focusedKey);
        if (card != null) {
            pushCounterChips(card);
        }
    }

    /** Sends one card's counter chips to the overlay, where they render above the card. */
    private void pushCounterChips(ActiveCard card) {
        CardCounters counters = store.get(card.printingId);
        List<String> labels = new ArrayList<>(counters.keywords);
        for (CardCounters.StatCounter stat : counters.stats) {
            labels.add(stat.count == 1 ? stat.label() : stat.label() + " ×" + stat.count);
        }
        if (counters.commanderCasts > 0) {
            labels.add(getString(R.string.ar_tax_chip, counters.commanderTax()));
        }
        String summary = counters.stats.isEmpty()
                ? ""
                : String.format("%+d/%+d", counters.netPower(), counters.netToughness());
        overlay.setChips(card.key, labels, summary);
    }

    private void addChip(String label, Runnable onRemove) {
        Button chip = new Button(this);
        chip.setText(getString(R.string.ar_chip_remove, label));
        chip.setAllCaps(false);
        chip.setOnClickListener(v -> {
            onRemove.run();
            persistAndRefresh();
        });
        chipRow.addView(chip);
    }

    // -------------------------------------------------------------------------------- GL renderer

    private final class SceneRenderer implements GLSurfaceView.Renderer {

        private final float[] viewMatrix = new float[16];
        private final float[] projectionMatrix = new float[16];
        private final float[] viewProjection = new float[16];

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            backgroundRenderer.createOnGlThread();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            viewportWidth = width;
            viewportHeight = height;
            viewportChanged = true;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            synchronized (sessionLock) {
                if (session == null) {
                    return;
                }
                if (viewportChanged) {
                    // The activity is locked to portrait, so the rotation is a constant.
                    session.setDisplayGeometry(Surface.ROTATION_0, viewportWidth, viewportHeight);
                    viewportChanged = false;
                }

                session.setCameraTextureName(backgroundRenderer.getTextureId());
                Frame frame;
                try {
                    frame = session.update();
                } catch (CameraNotAvailableException e) {
                    return;
                }

                backgroundRenderer.draw(frame);
                Camera camera = frame.getCamera();
                if (identifier != null) {
                    identifier.maybeIdentify(frame);
                }
                updateTrackedImages(frame);
                handlePendingTap(frame);
                overlay.postGeometry(computePoses(camera));
            }
        }

        private void updateTrackedImages(Frame frame) {
            for (AugmentedImage image : frame.getUpdatedTrackables(AugmentedImage.class)) {
                boolean solidLock = image.getTrackingState() == TrackingState.TRACKING
                        && image.getTrackingMethod()
                                == AugmentedImage.TrackingMethod.FULL_TRACKING;
                ActiveCard card = cardByPrinting.get(image.getName());
                if (!solidLock || card == null) {
                    continue;
                }

                boolean newLock = card.trackedImage == null
                        || !card.trackedImage.getName().equals(image.getName());
                card.trackedImage = image;
                card.halfWidthM = image.getExtentX() / 2f;
                card.halfHeightM = image.getExtentZ() / 2f;
                if (card.anchor != null) {
                    card.anchor.detach();
                    card.anchor = null;
                }

                if (newLock) {
                    // Show the printing that is actually on the table — its scan is downloaded.
                    Bitmap recognised = referenceBitmaps.get(image.getName());
                    if (recognised != null) {
                        overlay.upsertCard(card.key, recognised);
                    }
                }
                if (!image.getName().equals(card.printingId)) {
                    // A different printing of this card: its own counters take over.
                    card.printingId = image.getName();
                    runOnUiThread(() -> {
                        pushCounterChips(card);
                        if (card.key.equals(focusedKey)) {
                            refreshCounterUi();
                        }
                    });
                }
                markLocated(card);
            }
        }

        private void markLocated(ActiveCard card) {
            if (card.key.equals(pendingPlacementKey)) {
                pendingPlacementKey = null; // Tracking found it; no manual placement needed.
            }
            if (card.located) {
                return;
            }
            card.located = true;
            if (focusedKey == null) {
                focusedKey = card.key;
                overlay.setFocus(card.key);
            }
            runOnUiThread(() -> {
                if (card.key.equals(focusedKey)) {
                    panel.setVisibility(View.VISIBLE);
                    refreshCounterUi();
                }
                refreshPendingChips();
                updateStatusLine();
            });
        }

        /** Places the armed pending card — or a lone unlocated one — at the tapped surface. */
        private void handlePendingTap(Frame frame) {
            float x = pendingTapX;
            float y = pendingTapY;
            if (x < 0) {
                return;
            }
            pendingTapX = -1f;
            pendingTapY = -1f;

            ActiveCard target = null;
            String armed = pendingPlacementKey;
            if (armed != null) {
                target = cardsByKey.get(armed);
            } else {
                for (ActiveCard card : cardOrder) {
                    if (!card.located) {
                        if (target != null) {
                            return; // Several cards waiting: make the user pick a chip first.
                        }
                        target = card;
                    }
                }
            }
            if (target == null || target.trackedImage != null) {
                return;
            }

            for (HitResult hit : frame.hitTest(x, y)) {
                Trackable trackable = hit.getTrackable();
                boolean usable = (trackable instanceof Plane
                        && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
                        || trackable instanceof Point;
                if (usable) {
                    if (target.anchor != null) {
                        target.anchor.detach();
                    }
                    target.anchor = hit.createAnchor();
                    pendingPlacementKey = null;
                    markLocated(target);
                    return;
                }
            }
        }

        private List<CardOverlayView.CardPose> computePoses(Camera camera) {
            List<CardOverlayView.CardPose> poses = new ArrayList<>();
            if (camera.getTrackingState() != TrackingState.TRACKING) {
                return poses;
            }

            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);
            Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);

            for (ActiveCard card : cardOrder) {
                CardOverlayView.CardPose pose = computePose(card);
                if (pose != null) {
                    poses.add(pose);
                }
            }
            return poses;
        }

        private CardOverlayView.CardPose computePose(ActiveCard card) {
            Pose pose;
            if (card.trackedImage != null
                    && card.trackedImage.getTrackingState() == TrackingState.TRACKING
                    && card.trackedImage.getTrackingMethod()
                            == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                pose = card.trackedImage.getCenterPose();
            } else if (card.anchor != null
                    && card.anchor.getTrackingState() == TrackingState.TRACKING) {
                pose = card.anchor.getPose();
            } else {
                return null;
            }

            float halfWidth = card.halfWidthM;
            float halfHeight = card.halfHeightM;
            // The image lies in the pose's X-Z plane; -Z is the top edge of the artwork.
            float[][] localCorners = {
                    {-halfWidth, 0, -halfHeight},
                    {halfWidth, 0, -halfHeight},
                    {halfWidth, 0, halfHeight},
                    {-halfWidth, 0, halfHeight},
            };
            float[] corners = new float[8];
            for (int i = 0; i < 4; i++) {
                float[] screen = projectToScreen(pose.transformPoint(localCorners[i]));
                if (screen == null) {
                    return null;
                }
                corners[i * 2] = screen[0];
                corners[i * 2 + 1] = screen[1];
            }

            float[] center = projectToScreen(new float[] {pose.tx(), pose.ty(), pose.tz()});
            if (center == null) {
                return null;
            }

            float baseWidth = (float) Math.hypot(corners[2] - corners[0], corners[3] - corners[1]);
            return new CardOverlayView.CardPose(card.key, corners, center[0], center[1], baseWidth);
        }

        /** World point to screen pixels, or null when it is behind the camera. */
        private float[] projectToScreen(float[] world) {
            float[] clip = new float[4];
            Matrix.multiplyMV(clip, 0, viewProjection, 0,
                    new float[] {world[0], world[1], world[2], 1f}, 0);
            if (clip[3] <= 0) {
                return null;
            }
            float x = (clip[0] / clip[3] * 0.5f + 0.5f) * viewportWidth;
            float y = (0.5f - clip[1] / clip[3] * 0.5f) * viewportHeight;
            return new float[] {x, y};
        }
    }
}
