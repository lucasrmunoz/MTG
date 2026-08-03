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
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The AR screen: finds a known physical card through the camera and shows its virtual copy with
 * counters.
 *
 * Recognition is reference-image tracking only — the card's printings are registered as ARCore
 * augmented images at runtime — never "that object looks like a card". When recognition cannot
 * lock on (sleeves, glare, worn foils), tapping a surface places the card manually instead.
 * Counters are stored per printing and reattach whenever that printing is recognised again, which
 * is what lets you put the phone down mid-game and pick the state back up later.
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

    private GLSurfaceView surfaceView;
    private CardOverlayView overlay;
    private TextView statusText;
    private LinearLayout chipRow;
    private TextView taxLabel;

    private final Object sessionLock = new Object();
    private Session session;
    private boolean databaseAttached;
    private boolean installRequested;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

    private volatile boolean viewportChanged;
    private volatile int viewportWidth;
    private volatile int viewportHeight;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String cardId;
    private String cardName;
    private String cardImageUrl;
    private final Map<String, String> printingImageUrls = new ConcurrentHashMap<>();
    private final Map<String, Bitmap> referenceBitmaps = new ConcurrentHashMap<>();
    private volatile boolean imagesReady;

    /**
     * Bumped on every card switch; in-flight downloads and database builds for the previous card
     * check it and abandon their work instead of mixing two cards' images.
     */
    private volatile int cardGeneration;

    private CounterStore store;
    private volatile String activePrintingId;
    private CardCounters counters;
    private boolean saveFailureReported;

    // Written only by the GL thread.
    private AugmentedImage trackedImage;
    private Anchor manualAnchor;
    private volatile float pendingTapX = -1f;
    private volatile float pendingTapY = -1f;
    private Boolean lastShownTracking;

    /** Launched with no card: OCR titles off the camera until the user picks a candidate. */
    private boolean identifyMode;
    private CardIdentifier identifier;
    private volatile boolean cardSelected;
    /** Set on a card switch; the GL thread drops the old card's tracking when it sees it. */
    private volatile boolean trackingResetRequested;
    private LinearLayout candidateRow;
    private View candidatesScroll;
    private View panel;

    private static final int MAX_REFERENCE_IMAGES = 12;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_card);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!readIntentExtras()) {
            Toast.makeText(this, R.string.ar_bad_launch, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

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

        // The scanner runs in every session — also when opened from search — so pointing the
        // camera at a different card offers it as a switch chip instead of ignoring it.
        identifier = new CardIdentifier(executor, this::showCandidates);

        if (identifyMode) {
            panel.setVisibility(View.GONE);
            statusText.setText(R.string.ar_status_scan);
        } else {
            setActivePrinting(cardId);
            cardSelected = true;
            identifier.setRelaxed(true);
            statusText.setText(getString(R.string.ar_status_searching, cardName));
            final int generation = cardGeneration;
            executor.execute(() -> downloadImages(generation));
        }
    }

    /**
     * The activity draws edge-to-edge (Android 15 enforces it), so the camera view stays
     * full-bleed while the touchable chrome moves inside the system bars: the close button and
     * status line drop below the status bar and cutout, the counter panel rises above the
     * gesture-navigation bar.
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

    private boolean readIntentExtras() {
        cardId = getIntent().getStringExtra(EXTRA_CARD_ID);
        cardName = getIntent().getStringExtra(EXTRA_CARD_NAME);
        cardImageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        String printingsJson = getIntent().getStringExtra(EXTRA_PRINTINGS);
        if (cardId == null && cardName == null && cardImageUrl == null) {
            // No card at all: scan mode, where the camera decides which card this is.
            identifyMode = true;
            return true;
        }
        if (cardId == null || cardName == null || cardImageUrl == null) {
            return false;
        }

        printingImageUrls.put(cardId, cardImageUrl);
        if (printingsJson != null) {
            try {
                JSONArray printings = new JSONArray(printingsJson);
                for (int i = 0; i < printings.length(); i++) {
                    JSONObject printing = printings.getJSONObject(i);
                    printingImageUrls.putIfAbsent(
                            printing.getString("id"), printing.getString("imageUrl"));
                }
            } catch (JSONException e) {
                Log.w(TAG, "Ignoring malformed printings payload.", e);
            }
        }
        return true;
    }

    /** Downloads the display scan plus every reference image, then attaches the image database. */
    private void downloadImages(int generation) {
        try {
            Bitmap display = ImageFetcher.fetch(cardImageUrl);
            if (generation != cardGeneration) {
                return; // The user already switched to another card; this art would be wrong.
            }
            runOnUiThread(() -> overlay.setCardBitmap(display));
        } catch (IOException e) {
            Log.w(TAG, "Could not download the card scan.", e);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.ar_image_download_failed, Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }

        for (Map.Entry<String, String> entry : printingImageUrls.entrySet()) {
            if (generation != cardGeneration) {
                return;
            }
            try {
                referenceBitmaps.put(entry.getKey(), ImageFetcher.fetch(entry.getValue()));
            } catch (IOException e) {
                // One printing failing to download only means that copy will not be recognised.
                Log.w(TAG, "Skipping reference image for " + entry.getKey(), e);
            }
        }

        if (generation != cardGeneration) {
            return;
        }
        imagesReady = true;
        executor.execute(this::attachDatabaseIfPossible);
    }

    /**
     * Registers the downloaded printings as ARCore reference images. Runs on the executor: image
     * feature extraction takes tens of milliseconds per printing, too slow for the UI thread.
     */
    private void attachDatabaseIfPossible() {
        int generation = cardGeneration;
        synchronized (sessionLock) {
            if (session == null || databaseAttached || !imagesReady
                    || generation != cardGeneration) {
                return;
            }

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

            if (registered > 0) {
                session.configure(buildConfig(database));
                databaseAttached = true;
            }
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

        executor.execute(this::attachDatabaseIfPossible);
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
    public void onToggleMode() {
        overlay.setPlaced(!overlay.isPlaced());
    }

    @Override
    public void onManualPlace(float x, float y) {
        if (!cardSelected) {
            return; // Nothing to place until scan mode has identified a card.
        }
        pendingTapX = x;
        pendingTapY = y;
    }

    // -------------------------------------------------------------------------------- scan mode

    /**
     * Cards the camera has read so far, as tappable chips — minus the one already active, so the
     * row reads as "other cards you could switch to". Called off the main thread.
     */
    private void showCandidates(List<ScryfallLookup.CardSummary> candidates) {
        runOnUiThread(() -> {
            candidateRow.removeAllViews();
            int shown = 0;
            for (ScryfallLookup.CardSummary candidate : candidates) {
                if (candidate.name.equalsIgnoreCase(cardName)) {
                    continue;
                }
                Button chip = new Button(this);
                chip.setText(candidate.name);
                chip.setAllCaps(false);
                chip.setOnClickListener(v -> switchToCard(candidate));
                candidateRow.addView(chip);
                shown++;
            }
            candidatesScroll.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        });
    }

    /**
     * Makes this card the active one — the first pick in scan mode, or a mid-session switch when
     * the camera spotted a different card. Everything swaps: art, reference images, counters.
     */
    private void switchToCard(ScryfallLookup.CardSummary card) {
        if (card.id.equals(cardId)) {
            return;
        }
        cardSelected = true;
        identifier.setRelaxed(true);
        cardId = card.id;
        cardName = card.name;
        cardImageUrl = card.imageUrl;

        // Invalidate everything the previous card left in flight before repopulating.
        cardGeneration++;
        imagesReady = false;
        referenceBitmaps.clear();
        printingImageUrls.clear();
        printingImageUrls.put(card.id, card.imageUrl);
        synchronized (sessionLock) {
            databaseAttached = false;
        }
        trackingResetRequested = true;

        panel.setVisibility(View.VISIBLE);
        // Rebuild the chip row from everything spotted so far: the previous card becomes a chip,
        // making a switch back one tap.
        showCandidates(identifier.snapshot());
        setActivePrinting(card.id);
        lastShownTracking = null;
        statusText.setText(getString(R.string.ar_status_searching, cardName));

        // Art versions turn "this card" into "any printing of this card you might own". The
        // single-threaded executor serialises this before downloadImages touches the same map.
        final int generation = cardGeneration;
        executor.execute(() -> {
            try {
                for (ScryfallLookup.CardSummary version : ScryfallLookup.artVersions(card.name)) {
                    if (generation != cardGeneration
                            || printingImageUrls.size() >= MAX_REFERENCE_IMAGES) {
                        break;
                    }
                    printingImageUrls.putIfAbsent(version.id, version.imageUrl);
                }
            } catch (IOException e) {
                Log.w(TAG, "No art versions; tracking only the identified printing.", e);
            }
            downloadImages(generation);
        });
    }

    // ----------------------------------------------------------------------------- counter panel

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
        refreshCounterUi();
    }

    private void wireStatButton(int viewId, int power, int toughness) {
        findViewById(viewId).setOnClickListener(v -> {
            if (counters == null) {
                return; // Scan mode before a card is picked; the panel is hidden anyway.
            }
            counters.addStat(power, toughness);
            persistAndRefresh();
        });
    }

    private void adjustCommanderCasts(int delta) {
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

    /** Rebuilds the tappable chip row and pushes the same labels onto the AR overlay. */
    private void refreshCounterUi() {
        if (counters == null) {
            return; // Scan mode before a card is picked.
        }
        chipRow.removeAllViews();
        List<String> overlayChips = new ArrayList<>();

        for (String keyword : new ArrayList<>(counters.keywords)) {
            overlayChips.add(keyword);
            addChip(keyword, () -> counters.removeKeyword(keyword));
        }
        for (CardCounters.StatCounter stat : new ArrayList<>(counters.stats)) {
            String label = stat.count == 1 ? stat.label() : stat.label() + " ×" + stat.count;
            overlayChips.add(label);
            addChip(label, () -> counters.removeStat(stat.power, stat.toughness));
        }
        if (counters.commanderCasts > 0) {
            overlayChips.add(getString(R.string.ar_tax_chip, counters.commanderTax()));
        }

        String summary = counters.stats.isEmpty()
                ? ""
                : String.format("%+d/%+d", counters.netPower(), counters.netToughness());
        taxLabel.setText(getString(R.string.ar_tax_chip, counters.commanderTax()));
        overlay.setChips(overlayChips, summary);
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

    private void setActivePrinting(String printingId) {
        activePrintingId = printingId;
        counters = store.get(printingId);
        runOnUiThread(() -> {
            if (chipRow != null) {
                refreshCounterUi();
            }
        });
    }

    private void updateStatus(boolean tracking) {
        if (cardName == null) {
            return; // Scan mode owns the status line until a card is picked.
        }
        if (lastShownTracking != null && lastShownTracking == tracking) {
            return;
        }
        lastShownTracking = tracking;
        runOnUiThread(() -> statusText.setText(tracking
                ? getString(R.string.ar_status_tracking, cardName)
                : getString(R.string.ar_status_searching, cardName)));
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
                if (trackingResetRequested) {
                    trackingResetRequested = false;
                    trackedImage = null;
                    if (manualAnchor != null) {
                        manualAnchor.detach();
                        manualAnchor = null;
                    }
                }
                updateTrackedImage(frame);
                handlePendingTap(frame);
                CardOverlayView.OverlayState state = computeOverlayState(camera);
                updateStatus(state.tracking);
                overlay.postState(state);
            }
        }

        private void updateTrackedImage(Frame frame) {
            for (AugmentedImage image : frame.getUpdatedTrackables(AugmentedImage.class)) {
                boolean solidLock = image.getTrackingState() == TrackingState.TRACKING
                        && image.getTrackingMethod()
                                == AugmentedImage.TrackingMethod.FULL_TRACKING;
                // A stale update from before a card switch must not resurrect the old card.
                if (!solidLock || !printingImageUrls.containsKey(image.getName())) {
                    continue;
                }

                boolean newLock = trackedImage == null
                        || !trackedImage.getName().equals(image.getName());
                trackedImage = image;
                if (manualAnchor != null) {
                    manualAnchor.detach();
                    manualAnchor = null;
                }
                if (newLock) {
                    // Float the printing that is actually on the table, not whichever scan the
                    // card was opened with — its reference bitmap is already downloaded.
                    Bitmap recognised = referenceBitmaps.get(image.getName());
                    if (recognised != null) {
                        runOnUiThread(() -> overlay.setCardBitmap(recognised));
                    }
                }
                if (!image.getName().equals(activePrintingId)) {
                    // A different printing was recognised: its own counters take over.
                    setActivePrinting(image.getName());
                }
            }
        }

        private void handlePendingTap(Frame frame) {
            float x = pendingTapX;
            float y = pendingTapY;
            if (x < 0) {
                return;
            }
            pendingTapX = -1f;
            pendingTapY = -1f;
            if (trackedImage != null) {
                return; // Recognition already anchors the card; a manual anchor would fight it.
            }

            for (HitResult hit : frame.hitTest(x, y)) {
                Trackable trackable = hit.getTrackable();
                boolean usable = (trackable instanceof Plane
                        && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
                        || trackable instanceof Point;
                if (usable) {
                    if (manualAnchor != null) {
                        manualAnchor.detach();
                    }
                    manualAnchor = hit.createAnchor();
                    return;
                }
            }
        }

        private CardOverlayView.OverlayState computeOverlayState(Camera camera) {
            CardOverlayView.OverlayState notTracking =
                    new CardOverlayView.OverlayState(false, null, 0, 0, 0);
            if (camera.getTrackingState() != TrackingState.TRACKING) {
                return notTracking;
            }

            Pose pose;
            float halfWidth;
            float halfHeight;
            if (trackedImage != null && trackedImage.getTrackingState() == TrackingState.TRACKING
                    && trackedImage.getTrackingMethod()
                            == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                pose = trackedImage.getCenterPose();
                halfWidth = trackedImage.getExtentX() / 2f;
                halfHeight = trackedImage.getExtentZ() / 2f;
            } else if (manualAnchor != null
                    && manualAnchor.getTrackingState() == TrackingState.TRACKING) {
                pose = manualAnchor.getPose();
                halfWidth = CARD_WIDTH_M / 2f;
                halfHeight = CARD_HEIGHT_M / 2f;
            } else {
                return notTracking;
            }

            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);
            Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);

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
                    return notTracking;
                }
                corners[i * 2] = screen[0];
                corners[i * 2 + 1] = screen[1];
            }

            float[] center = projectToScreen(
                    new float[] {pose.tx(), pose.ty(), pose.tz()});
            if (center == null) {
                return notTracking;
            }

            float baseWidth = (float) Math.hypot(corners[2] - corners[0], corners[3] - corners[1]);
            return new CardOverlayView.OverlayState(true, corners, center[0], center[1], baseWidth);
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
