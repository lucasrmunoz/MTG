package com.lucasmunoz.mtg.ar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
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
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** JSON array of the card's own keywords, for glossary popups. */
    public static final String EXTRA_KEYWORDS = "keywords";
    /** JSON array of players — presence switches the screen into Commander game mode. */
    public static final String EXTRA_GAME_PLAYERS = "gamePlayers";
    /** JSON array of players with their latest life and casts, published via setResult. */
    public static final String EXTRA_GAME_RESULT = "gameResult";

    private static final String TAG = "ArCardActivity";

    /** A Magic card is 63mm x 88mm; ARCore wants the physical width of every reference image. */
    private static final float CARD_WIDTH_M = 0.063f;
    private static final float CARD_HEIGHT_M = 0.088f;
    /** How high a Flying card hovers above its anchor — enough to read as airborne. */
    private static final float FLY_HEIGHT_M = 0.05f;
    /** A flyer bobs ± this much around FLY_HEIGHT_M so the hover is visible motion, not just
     *  scale — straight-down views project pure lift as nothing but a bigger card. */
    private static final float FLY_BOB_M = 0.012f;
    private static final long FLY_BOB_PERIOD_MS = 2600;
    /** A faked sun angle pushes the shadow sideways out from under the card; straight down it
     *  would hide entirely behind the lifted quad and the altitude would not read. */
    private static final float SHADOW_OFFSET_M = 0.02f;
    /** Shadows shrink a little with altitude, reinforcing the height read. */
    private static final float SHADOW_SCALE = 0.85f;

    private static final int CAMERA_PERMISSION_CODE = 41;
    private static final int MAX_REFERENCE_IMAGES_PER_CARD = 12;
    /**
     * Game mode tracks up to six commanders at once, so each gets fewer reference images than a
     * single-card session — six cards at twelve images each would stall feature extraction.
     */
    private static final int GAME_MAX_REFERENCE_IMAGES_PER_CARD = 4;

    /** One card on (or headed for) the table. Tracking fields are guarded by sessionLock. */
    private static final class ActiveCard {
        final String key;
        final String name;
        volatile String printingId;
        /** The card's own keyword abilities, offered with glossary definitions in the panel. */
        final List<String> keywords = new CopyOnWriteArrayList<>();
        final Map<String, String> printingUrls = new ConcurrentHashMap<>();
        /** Type line and set name for the card list; empty until a Scryfall response says. */
        volatile String typeLine = "";
        volatile String setName = "";
        volatile boolean located;
        volatile float halfWidthM = CARD_WIDTH_M / 2f;
        volatile float halfHeightM = CARD_HEIGHT_M / 2f;
        /** Render-affecting abilities, from printed keywords plus keyword counters. Written on
         *  the UI thread by updateAbilityFlags; the GL thread only reads them. */
        volatile boolean flying;
        volatile boolean reach;
        /** The scan the overlay, card list and tokens show — the only decoded bitmap a card
         *  keeps. Written on the executor, read anywhere. */
        volatile Bitmap displayBitmap;
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
    private KeywordWheelView keywordWheel;
    /** Read on the GL thread: while the wheel is up, new-card scanning pauses so mid-edit
     *  frames can't spawn cards or duplicate-scan toasts under the menu. */
    private volatile boolean keywordWheelOpen;

    /** Plane finding stays hot this long after the last placement-intent signal — continuous
     *  plane detection is CPU work the table only needs around placements. */
    private static final long PLANE_IDLE_AFTER_MS = 20_000;

    /** When a surface placement last became plausible: session start, the card list opening,
     *  a placement arming, a token drag starting, a scan action. Read on the GL thread. */
    private volatile long lastPlacementIntentMs = SystemClock.elapsedRealtime();

    /** Mirrors the plane-finding mode the session's config currently carries. Flipped only on
     *  the GL thread; the reconfigure itself runs on the database executor. */
    private volatile boolean planeFindingOn = true;

    private static final String PREF_ANIMATIONS = "animations";
    /** Ambient animations (the Flying bob). Read on the GL thread per frame, toggled from the
     *  Motion pill on the UI thread; off leaves flyers at the static lifted pose. */
    private volatile boolean animationsEnabled = true;
    private TextView statusText;
    private LinearLayout chipRow;
    private TextView taxLabel;
    private TextView lifeLabel;
    private View lifeRow;
    private View statRow;
    private View chipScroll;
    private View actionRow;
    private View keywordButton;
    /** Game mode only: binds the focused scanned card to a player as their commander. */
    private View setCommanderButton;
    private Button cardsToggle;
    private View cardListScroll;
    private LinearLayout cardList;
    private boolean cardListOpen;
    private View panel;
    /** Names what the panel is editing: the focused card, or the focused player in game mode. */
    private TextView panelTitle;
    /** The header chevron mirroring the panel's collapsed/expanded state. */
    private TextView panelExpandChevron;
    /** Two-stage panel: collapsed keeps only the essential row so the camera view stays clear
     *  on small screens; tapping the header expands the full editor. Sticky for the session. */
    private boolean panelExpanded;

    /** Non-null only in Commander game mode; the web layer owns it, this screen edits a copy. */
    private GameSession game;
    /** Which player each game-mode card belongs to, by the card's composite key. */
    private final Map<String, GamePlayer> playersByCardKey = new ConcurrentHashMap<>();
    /** The focused player in game mode, settable by card focus or a life-token tap. */
    private volatile int focusedPlayerId = -1;

    /** A life token next to 63mm cards; its world anchors live and die with the session. */
    private static final float TOKEN_WIDTH_M = 0.06f;
    private final Map<Integer, Anchor> tokenAnchors = new ConcurrentHashMap<>();
    /** A dropped token waiting for its GL-thread hit test; -1 when none. */
    private volatile int pendingTokenPlayerId = -1;
    private volatile float pendingTokenX;
    private volatile float pendingTokenY;

    /** Guide-box scanning: reads confined to a grey on-screen outline the user aims a card at.
     *  Volatile — the scanner's lookup thread reads it to decide on confirmation feedback. */
    private volatile boolean guideMode;
    /** The guide outline's width as a share of the screen; height follows the 63:88 card. */
    private static final float GUIDE_WIDTH_FRAC = 0.66f;
    /** The outline's vertical centre, above the bottom panel and below the status line. */
    private static final float GUIDE_CENTER_Y_FRAC = 0.40f;

    /** Cards already celebrated this guide session — one buzz per card, not per lookup. */
    private final Set<String> guideConfirmedNames = ConcurrentHashMap.newKeySet();
    /** While now is before this, the status line shows a "✓ card" flash; updates hold off. */
    private long statusFlashUntilMs;
    private static final long STATUS_FLASH_MS = 1800;

    /** Set while the "same card again" toast invitation stands; an empty tap consumes it. */
    private volatile boolean duplicateTapArmed;
    /** A card reopened by that tap: its next confirmation flashes like a fresh scan. */
    private volatile String rearmedScanName;
    /** When the duplicate toast last showed; re-reads recur every guided pass, ~3 a second. */
    private long lastDuplicateToastMs;
    private static final long DUPLICATE_TOAST_INTERVAL_MS = 3500;

    private final Object sessionLock = new Object();
    private Session session;
    private boolean installRequested;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

    private volatile boolean viewportChanged;
    private volatile int viewportWidth;
    private volatile int viewportHeight;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Scanning runs on its own thread: an adopted card queues minutes of image downloads and
     *  database rebuilds on the main executor, which must never starve the next scan pass. */
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private CardIdentifier identifier;
    /** Non-null only in debuggable builds: the phase-0 scan-corpus capture affordance. */
    private FrameCorpusRecorder frameRecorder;
    private CounterStore store;
    private KeywordGlossary glossary;
    private boolean saveFailureReported;

    private final Map<String, ActiveCard> cardsByKey = new ConcurrentHashMap<>();
    private final List<ActiveCard> cardOrder = new CopyOnWriteArrayList<>();
    /** Which card each registered reference image belongs to, by augmented-image name. */
    private final Map<String, ActiveCard> cardByPrinting = new ConcurrentHashMap<>();

    /** Downloads card scans through a disk cache; decoded bitmaps are not retained. */
    private ImageFetcher images;
    /** The reference-image database, grown incrementally by syncDatabase. Executor only. */
    private AugmentedImageDatabase database;
    /** Printings already registered in {@link #database}. Executor only. */
    private final Set<String> registeredPrintings = new HashSet<>();
    /** Set when a card was removed or the session recreated: the next sync rebuilds the
     *  database from scratch, because ARCore's database has no removeImage. */
    private volatile boolean rebuildRequired;

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
        lifeLabel = findViewById(R.id.ar_life_label);
        lifeRow = findViewById(R.id.ar_life_row);
        statRow = findViewById(R.id.ar_stat_row);
        chipScroll = findViewById(R.id.ar_chip_scroll);
        actionRow = findViewById(R.id.ar_action_row);
        keywordButton = findViewById(R.id.ar_keyword);
        setCommanderButton = findViewById(R.id.ar_set_commander);
        panelExpandChevron = findViewById(R.id.ar_panel_expand);
        findViewById(R.id.ar_panel_header).setOnClickListener(v -> {
            panelExpanded = !panelExpanded;
            refreshCounterUi();
        });
        cardsToggle = findViewById(R.id.ar_cards_toggle);
        cardListScroll = findViewById(R.id.ar_card_list_scroll);
        cardList = findViewById(R.id.ar_card_list);
        cardsToggle.setOnClickListener(v -> {
            cardListOpen = !cardListOpen;
            notePlacementIntent(); // Browsing the list often ends in a tap-to-place.
            refreshCardList();
        });
        findViewById(R.id.ar_rescan).setOnClickListener(v -> {
            if (identifier != null) {
                identifier.rescan();
                notePlacementIntent();
                Toast.makeText(this, R.string.ar_rescan_toast, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.ar_guide).setOnClickListener(v -> {
            toggleGuideBox((Button) v);
            notePlacementIntent();
        });
        wireAnimationsToggle();
        panel = findViewById(R.id.ar_panel);
        panelTitle = findViewById(R.id.ar_panel_title);
        overlay = findViewById(R.id.ar_overlay);
        overlay.setListener(this);
        keywordWheel = findViewById(R.id.ar_keyword_wheel);
        keywordWheel.setListener(new KeywordWheelView.Listener() {
            @Override
            public void onEntrySelected(KeywordWheelView.Entry entry) {
                CardCounters counters = focusedCounters();
                if (counters == null) {
                    return;
                }
                if (entry.custom) {
                    showCustomKeywordDialog();
                    return;
                }
                if (entry.active) {
                    counters.removeKeyword(entry.label);
                } else {
                    counters.addKeyword(entry.label);
                }
                persistAndRefresh();
                // The wheel stays up for more edits; re-highlight the toggled segment.
                keywordWheel.refreshEntries(buildWheelEntries());
            }

            @Override
            public void onStatApplied(int power, int toughness) {
                CardCounters counters = focusedCounters();
                if (counters == null) {
                    return;
                }
                counters.applyStatDelta(power, toughness);
                persistAndRefresh();
            }

            @Override
            public void onDismissed() {
                keywordWheelOpen = false;
                overlay.setTouchRelay(null);
            }
        });

        surfaceView = findViewById(R.id.ar_surface);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(new SceneRenderer());
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        applySystemBarInsets();
        store = new CounterStore(getFilesDir());
        images = new ImageFetcher(new File(getCacheDir(), "card-scans"));
        executor.execute(images::trim);
        glossary = new KeywordGlossary(this);
        wireCounterControls();
        panel.setVisibility(View.GONE);

        String gameJson = getIntent().getStringExtra(EXTRA_GAME_PLAYERS);
        if (gameJson != null) {
            try {
                game = GameSession.fromJson(gameJson);
            } catch (JSONException e) {
                // Our own web code sent garbage: fail fast, the plugin resolves with the
                // players it was given (no result extra means "nothing changed").
                Log.w(TAG, "Malformed game payload.", e);
                Toast.makeText(this, R.string.ar_bad_game_launch, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        // The scanner always runs, in game mode too: any table card can join the scene with
        // its own counters. Commander names are filtered out of its candidates — those are
        // already tracked under per-player keys. The name catalog is what lets it match
        // whole cards locally; loading it races the user's first aim and usually wins.
        CardNameCatalog catalog =
                new CardNameCatalog(new File(getFilesDir(), "scryfall-card-names.json"));
        scanExecutor.execute(catalog::ensureLoaded);
        identifier = new CardIdentifier(
                scanExecutor, catalog, this::onCandidatesRecognized, this::onGuideRead,
                this::onDuplicateScan);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            wireCorpusCapture();
        }

        if (game != null) {
            enterGameMode();
            // Game mode scans the same way: start aimed, "✕ Outline" drops to ambient.
            overlay.post(this::enableGuideByDefault);
        } else {
            statusText.setText(R.string.ar_status_scan);
            addCardFromIntent();
        }
    }

    /** Turns the guide box on once the overlay has a size; re-posts until layout has run. */
    private void enableGuideByDefault() {
        if (guideMode || isFinishing()) {
            return;
        }
        if (overlay.getWidth() == 0) {
            overlay.post(this::enableGuideByDefault);
            return;
        }
        toggleGuideBox(findViewById(R.id.ar_guide));
    }

    /**
     * Commander game mode: the panel trades stat and keyword counters for a life stepper, and
     * every player's commander is registered for tracking up front. The latest state is
     * published as the activity result after every change, so closing this screen in any way
     * hands the web layer the freshest values.
     */
    private void enterGameMode() {
        publishGameResult();
        for (GamePlayer player : game.players()) {
            // Every player gets a life token in the tray, commander or not — life stays
            // visible and adjustable even when nothing is tracking.
            overlay.upsertToken(player.id, player.name, null, player.life, player.commanderTax());
            if (player.hasCard()) {
                adoptGameCard(player);
            }
        }
        updateStatusLine();
    }

    /** A card opened from search joins immediately, with its art versions sent by the web side. */
    private void addCardFromIntent() {
        String cardId = getIntent().getStringExtra(EXTRA_CARD_ID);
        String cardName = getIntent().getStringExtra(EXTRA_CARD_NAME);
        String imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        if (cardId == null || cardName == null || imageUrl == null) {
            return; // The plugin validates these; the scanner alone still works without them.
        }

        ActiveCard card = new ActiveCard(cardId, cardName, imageUrl);
        String printingsJson = getIntent().getStringExtra(EXTRA_PRINTINGS);
        if (printingsJson != null) {
            try {
                JSONArray printings = new JSONArray(printingsJson);
                for (int i = 0; i < printings.length()
                        && card.printingUrls.size() < maxReferenceImagesPerCard(); i++) {
                    JSONObject printing = printings.getJSONObject(i);
                    card.printingUrls.putIfAbsent(
                            printing.getString("id"), printing.getString("imageUrl"));
                }
            } catch (JSONException e) {
                Log.w(TAG, "Ignoring malformed printings payload.", e);
            }
        }

        String keywordsJson = getIntent().getStringExtra(EXTRA_KEYWORDS);
        if (keywordsJson != null) {
            try {
                JSONArray keywords = new JSONArray(keywordsJson);
                for (int i = 0; i < keywords.length(); i++) {
                    card.keywords.add(keywords.getString(i));
                }
            } catch (JSONException e) {
                Log.w(TAG, "Ignoring malformed keywords payload.", e);
            }
        }
        adoptCard(card, false);
    }

    /**
     * Toggles guide-box scanning: a grey card-aspect outline appears mid-screen and the scanner
     * only reads inside it — the frame is cropped to the box, contrast-stretched and read in
     * both orientations, all describing the one aimed card. The deliberate, Mythic-Tools-style
     * alternative to ambient scanning; ambient behaviour returns on toggle off.
     */
    private void toggleGuideBox(Button button) {
        float width = overlay.getWidth();
        float height = overlay.getHeight();
        if (identifier == null || width == 0 || height == 0) {
            return; // Not laid out yet; a tap this early has nothing to aim at anyway.
        }
        guideMode = !guideMode;
        if (guideMode) {
            guideConfirmedNames.clear();
            float boxWidth = width * GUIDE_WIDTH_FRAC;
            float boxHeight = boxWidth * CARD_HEIGHT_M / CARD_WIDTH_M;
            float centerX = width / 2f;
            float centerY = height * GUIDE_CENTER_Y_FRAC;
            float[] box = {
                    centerX - boxWidth / 2f, centerY - boxHeight / 2f,
                    centerX + boxWidth / 2f, centerY + boxHeight / 2f,
            };
            overlay.setGuideBox(box);
            identifier.setGuideBox(box);
            button.setText(R.string.ar_guide_active);
        } else {
            overlay.setGuideBox(null);
            identifier.setGuideBox(null);
            button.setText(R.string.ar_guide);
        }
        updateStatusLine();
    }

    /**
     * The phase-0 corpus capture toggle (docs/proposals/full-card-scanning.md), debug builds
     * only: while recording, the GL thread hands Y-plane frames to the recorder, which files
     * them under the app's external files so they can be pulled to the desktop replay harness.
     */
    private void wireCorpusCapture() {
        File external = getExternalFilesDir("scan-corpus");
        File corpusDir = external != null ? external : new File(getFilesDir(), "scan-corpus");
        Button capture = findViewById(R.id.ar_capture);
        frameRecorder = new FrameCorpusRecorder(corpusDir, new FrameCorpusRecorder.Listener() {
            @Override
            public void onFrameSaved(int savedCount) {
                runOnUiThread(() -> {
                    if (frameRecorder.isRecording()) {
                        capture.setText(getString(R.string.ar_capture_recording, savedCount));
                    }
                });
            }

            @Override
            public void onSaveFailed(Exception e) {
                runOnUiThread(() -> {
                    capture.setText(R.string.ar_capture);
                    Toast.makeText(ArCardActivity.this,
                            R.string.ar_capture_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
        capture.setVisibility(View.VISIBLE);
        capture.setOnClickListener(v -> {
            boolean start = !frameRecorder.isRecording();
            frameRecorder.setRecording(start);
            if (start) {
                capture.setText(getString(
                        R.string.ar_capture_recording, frameRecorder.savedCount()));
            } else {
                capture.setText(R.string.ar_capture);
                Toast.makeText(this, getString(R.string.ar_capture_saved_toast,
                        frameRecorder.savedCount(), frameRecorder.directory()),
                        Toast.LENGTH_LONG).show();
            }
        });
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

            // The bottom chrome floats as rounded panels, so it keeps a margin inside the bars.
            int pad = (int) (8 * density);
            bottom.setPadding(bars.left + pad, 0, bars.right + pad, bars.bottom + pad);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ------------------------------------------------------------------------------ card joining

    /** Every card the scanner has confirmed so far; new ones join the scene automatically. */
    private void onCandidatesRecognized(List<ScryfallLookup.CardSummary> candidates) {
        for (ScryfallLookup.CardSummary candidate : candidates) {
            if (isCommanderName(candidate.name)) {
                // Commanders are already tracked under composite per-player keys; letting OCR
                // enrich them with raw printing ids would corrupt that keying.
                continue;
            }
            if (candidate.name.equalsIgnoreCase(rearmedScanName)) {
                // The card reopened by a "scan it again" tap confirmed once more: same flash
                // and buzz as a fresh scan, even though nothing about the card is new.
                rearmedScanName = null;
                maybeFlashGuideConfirm(candidate);
            }
            ActiveCard existing = findCardByName(candidate.name);
            if (existing == null) {
                ActiveCard added =
                        new ActiveCard(candidate.id, candidate.name, candidate.imageUrl);
                added.keywords.addAll(candidate.keywords);
                added.typeLine = candidate.typeLine;
                added.setName = candidate.setName;
                runOnUiThread(() -> adoptCard(added, true));
                maybeFlashGuideConfirm(candidate);
                // A new card confirming makes any standing "same card again" invitation stale.
                duplicateTapArmed = false;
            } else if (existing.printingUrls.putIfAbsent(candidate.id, candidate.imageUrl)
                    == null) {
                maybeFlashGuideConfirm(candidate);
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

    /**
     * The scanner just read text inside the guide outline: flash the box green so the user
     * knows the aim is right. The only live scan feedback — everything else waits for a card
     * to actually confirm.
     */
    private void onGuideRead() {
        if (guideMode) {
            overlay.markGuideBoxActive();
        }
    }

    /**
     * A guided pass re-read the card that just confirmed. The scanner never re-matches it on
     * its own — instead the user is invited to tap the screen when it truly is a second copy
     * (or the same card, deliberately again). Off the main thread; the toast is throttled
     * because re-reads recur every pass while the camera lingers.
     */
    private void onDuplicateScan(String cardName) {
        duplicateTapArmed = true;
        runOnUiThread(() -> {
            if (!guideMode) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastDuplicateToastMs < DUPLICATE_TOAST_INTERVAL_MS) {
                return;
            }
            lastDuplicateToastMs = now;
            Toast.makeText(this, getString(R.string.ar_duplicate_scan, cardName),
                    Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * The "got it" moment of guide-box scanning: the first time an aimed card confirms — by
     * name or by exact printing — the status line flashes it with a tap of haptics, telling
     * the user to move to the next card. Off the main thread; once per card per session.
     */
    private void maybeFlashGuideConfirm(ScryfallLookup.CardSummary card) {
        if (!guideMode || !guideConfirmedNames.add(card.name)) {
            return;
        }
        runOnUiThread(() -> {
            String label = card.setName.isEmpty() ? card.name
                    : card.name + " — " + card.setName;
            statusText.setText(getString(R.string.ar_guide_confirmed, label));
            statusFlashUntilMs = SystemClock.uptimeMillis() + STATUS_FLASH_MS;
            statusText.postDelayed(this::updateStatusLine, STATUS_FLASH_MS);
            statusText.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.VIRTUAL_KEY);
        });
    }

    private boolean isCommanderName(String name) {
        if (game == null) {
            return false;
        }
        for (GamePlayer player : game.players()) {
            if (player.cardName != null && player.cardName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** A game-mode card owned by a player, as opposed to one the scanner found on the table. */
    private boolean isGameCard(String key) {
        return playersByCardKey.containsKey(key);
    }

    /** Game mode shares the image budget across up to six commanders plus scanned cards. */
    private int maxReferenceImagesPerCard() {
        return game != null ? GAME_MAX_REFERENCE_IMAGES_PER_CARD : MAX_REFERENCE_IMAGES_PER_CARD;
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
        updateAbilityFlags(card);
        overlay.upsertCard(card.key, null);
        pushCounterChips(card);
        refreshCardList();

        executor.execute(() -> {
            if (fetchArtVersions) {
                try {
                    for (ScryfallLookup.CardSummary version
                            : ScryfallLookup.artVersions(card.name)) {
                        if (card.typeLine.isEmpty() && !version.typeLine.isEmpty()) {
                            card.typeLine = version.typeLine;
                        }
                        if (card.printingUrls.size() >= maxReferenceImagesPerCard()) {
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

    /**
     * Registers one player's commander for tracking. Every key — the card's, its printings',
     * the reference images' — is the composite "playerId|printingId", so two players running
     * the same commander stay collision-free end to end. Which physical copy then binds to
     * which player is arbitrary for identical printings; the placement chips are the deliberate
     * escape hatch.
     */
    private void adoptGameCard(GamePlayer player) {
        String key = player.id + "|" + player.cardId;
        ActiveCard card = new ActiveCard(key, player.cardName, player.cardImageUrl);
        if (cardsByKey.putIfAbsent(key, card) != null) {
            return;
        }
        playersByCardKey.put(key, player);
        cardOrder.add(card);
        cardByPrinting.put(key, card);
        updateAbilityFlags(card);
        overlay.upsertCard(key, null);
        pushGameChips(card, player);
        refreshCardList();

        executor.execute(() -> {
            Bitmap tokenArt = null;
            if (player.cardArtCropUrl != null) {
                try {
                    tokenArt = images.fetch(player.cardArtCropUrl);
                } catch (IOException e) {
                    Log.w(TAG, "No art crop for the life token; the scan stands in.", e);
                }
            }

            try {
                for (ScryfallLookup.CardSummary version
                        : ScryfallLookup.artVersions(card.name)) {
                    if (card.typeLine.isEmpty() && !version.typeLine.isEmpty()) {
                        card.typeLine = version.typeLine;
                    }
                    // Commanders arrive over the bridge without keywords; the first art version
                    // supplies them, so a Flying commander renders airborne like any card.
                    if (card.keywords.isEmpty() && !version.keywords.isEmpty()) {
                        card.keywords.addAll(version.keywords);
                        runOnUiThread(() -> updateAbilityFlags(card));
                    }
                    if (card.printingUrls.size() >= GAME_MAX_REFERENCE_IMAGES_PER_CARD) {
                        break;
                    }
                    String versionKey = player.id + "|" + version.id;
                    if (card.printingUrls.putIfAbsent(versionKey, version.imageUrl) == null) {
                        cardByPrinting.put(versionKey, card);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "No art versions; tracking only the chosen printing.", e);
            }
            downloadImages(card);

            Bitmap art = tokenArt != null ? tokenArt : card.displayBitmap;
            if (art != null) {
                Bitmap finalArt = art;
                // Life and tax are read on the UI thread, where all their mutations happen.
                runOnUiThread(() -> overlay.upsertToken(
                        player.id, tokenName(player), finalArt, player.life,
                        player.commanderTax()));
            }
        });
    }

    /** Shows the card's scan (disk-cached) and registers any new printings for tracking. */
    private void downloadImages(ActiveCard card) {
        updateDisplayBitmap(card);
        syncDatabase();
    }

    /**
     * Decodes the scan the card should display — the printing the counters currently key on,
     * falling back to the card's own key — and hands it to the overlay and card list. The one
     * decoded bitmap a card retains; everything else lives in the disk cache.
     */
    private void updateDisplayBitmap(ActiveCard card) {
        Bitmap display = decodePrinting(card, card.printingId);
        if (display == null) {
            display = decodePrinting(card, card.key);
        }
        if (display != null) {
            card.displayBitmap = display;
            overlay.upsertCard(card.key, display);
        } else {
            Log.w(TAG, "No scan could be downloaded for " + card.name);
        }
        // The card list shows these scans as thumbnails; an open list picks them up now.
        refreshCardList();
    }

    /** One printing's scan via the disk cache, or null when it cannot be fetched or decoded. */
    private Bitmap decodePrinting(ActiveCard card, String printingId) {
        String url = card.printingUrls.get(printingId);
        if (url == null) {
            return null;
        }
        try {
            return images.fetch(url);
        } catch (IOException e) {
            Log.w(TAG, "Could not fetch the scan for " + printingId, e);
            return null;
        }
    }

    /**
     * Brings the reference-image database up to date with the current cards. Runs on the
     * executor, whose single thread owns {@link #database} and {@link #registeredPrintings}.
     *
     * New printings are fetched through the disk cache and added incrementally — feature
     * extraction (20-30ms per image) runs only for images the database has never seen, and
     * entirely outside the session lock, so the GL thread keeps rendering while it happens;
     * configure() copies the database, so mutating it here never touches the live session. A
     * removal instead rebuilds from scratch: ARCore's database has no removeImage.
     *
     * Cards already tracking keep their place — the pose moves onto a world anchor before the
     * session reconfigures, because a database swap stops all image tracking until re-detection.
     */
    private void syncDatabase() {
        Session current;
        synchronized (sessionLock) {
            current = session;
        }
        if (current == null) {
            return; // ensureSession schedules another sync once the session exists.
        }

        boolean mustConfigure = false;
        if (rebuildRequired) {
            rebuildRequired = false;
            database = null;
            registeredPrintings.clear();
            mustConfigure = true;
        }
        if (database == null) {
            database = new AugmentedImageDatabase(current);
        }

        for (ActiveCard card : cardOrder) {
            for (Map.Entry<String, String> printing : card.printingUrls.entrySet()) {
                if (!registeredPrintings.add(printing.getKey())) {
                    continue;
                }
                Bitmap scan;
                try {
                    scan = images.fetch(printing.getValue());
                } catch (IOException e) {
                    // This copy just won't be recognised now; unregistering lets a later sync
                    // retry once the network is back.
                    Log.w(TAG, "Skipping reference image for " + printing.getKey(), e);
                    registeredPrintings.remove(printing.getKey());
                    continue;
                }
                try {
                    database.addImage(printing.getKey(), scan, CARD_WIDTH_M);
                    mustConfigure = true;
                } catch (ImageInsufficientQualityException e) {
                    // A low-detail scan (full-art lands, mostly) cannot be tracked; the card can
                    // still be placed manually. Stays registered so it is not retried.
                    Log.w(TAG, "Not enough features to track printing " + printing.getKey());
                }
            }
        }
        if (!mustConfigure) {
            return;
        }

        synchronized (sessionLock) {
            if (session == null) {
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

    /**
     * Caps camera capture at 30 fps and refuses the depth sensor. ARCore prioritises 60 fps
     * and depth-sensor configs on devices that offer them; both cost battery and heat and
     * neither helps a card table. Must run before the session's first resume, while no
     * trackables exist yet.
     */
    private static void applyCoolCameraConfig(Session session) {
        Size defaultImageSize = session.getCameraConfig().getImageSize();
        CameraConfigFilter filter = new CameraConfigFilter(session);
        filter.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));
        filter.setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE));
        List<CameraConfig> configs = session.getSupportedCameraConfigs(filter);
        if (configs.isEmpty()) {
            return;
        }
        // Keep the default CPU image resolution — OCR and image tracking both feed on it;
        // only the frame rate and depth usage should change.
        CameraConfig chosen = configs.get(0);
        for (CameraConfig candidate : configs) {
            if (candidate.getImageSize().equals(defaultImageSize)) {
                chosen = candidate;
                break;
            }
        }
        session.setCameraConfig(chosen);
    }

    private Config buildConfig(AugmentedImageDatabase database) {
        Config config = new Config(session);
        config.setFocusMode(Config.FocusMode.AUTO);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        config.setPlaneFindingMode(planeFindingOn
                ? Config.PlaneFindingMode.HORIZONTAL : Config.PlaneFindingMode.DISABLED);
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
                applyCoolCameraConfig(session);
                session.configure(buildConfig(null));
                // A new session starts with an empty database; the sync below rebuilds it from
                // whatever cards are already adopted.
                rebuildRequired = true;
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

        executor.execute(this::syncDatabase);
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
        if (frameRecorder != null) {
            frameRecorder.close();
        }
        scanExecutor.shutdown();
        // A database sync may still be queued, and it uses the session outside the lock; closing
        // the session as the executor's last task runs strictly after every queued sync.
        executor.execute(() -> {
            synchronized (sessionLock) {
                if (session != null) {
                    session.close();
                    session = null;
                }
            }
        });
        executor.shutdown();
    }

    // ------------------------------------------------------------------ CardOverlayView.Listener

    @Override
    public void onFocusChanged(String key) {
        focusedKey = key;
        if (game != null) {
            GamePlayer player = playersByCardKey.get(key);
            if (player != null) {
                focusedPlayerId = player.id;
                overlay.setFocusedToken(player.id);
            } else {
                // A scanned table card took focus: the panel shows its counters, no player.
                focusedPlayerId = -1;
                overlay.setFocusedToken(-1);
            }
        }
        runOnUiThread(() -> {
            panel.setVisibility(View.VISIBLE);
            refreshCounterUi();
            updateStatusLine();
        });
    }

    @Override
    public void onTapEmpty(float x, float y) {
        // The invited "scan it again" tap — but an explicitly armed chip placement outranks it.
        if (duplicateTapArmed && pendingPlacementKey == null) {
            duplicateTapArmed = false;
            String reopened = identifier.rescanMostRecent();
            if (reopened != null) {
                // Let the confirm flash celebrate this card once more when it re-matches.
                guideConfirmedNames.remove(reopened);
                rearmedScanName = reopened;
                return;
            }
        }
        // Placement needs a hit test against the AR frame, which only the GL thread can run.
        pendingTapX = x;
        pendingTapY = y;
    }

    @Override
    public void onCardLongPressed(String key, float x, float y) {
        showKeywordWheel(x, y, true);
    }

    @Override
    public void onTokenTapped(int playerId) {
        if (game == null) {
            return;
        }
        focusedPlayerId = playerId;
        overlay.setFocusedToken(playerId);

        // Selecting the token also selects the player's tracked card, when there is one, so the
        // orange focus border and the panel agree about who is selected. Otherwise any card
        // focus is cleared — a player is selected now, not a scanned card.
        GamePlayer player = game.playerById(playerId);
        String key = player != null && player.hasCard() ? playerId + "|" + player.cardId : null;
        if (key != null && cardsByKey.containsKey(key)) {
            focusedKey = key;
            overlay.setFocus(key);
        } else {
            focusedKey = null;
            overlay.setFocus(null);
        }
        panel.setVisibility(View.VISIBLE);
        refreshCounterUi();
        updateStatusLine();
    }

    @Override
    public void onTokenDragStarted() {
        // The drop's hit test is seconds away; wake plane finding so the table is mapped.
        notePlacementIntent();
    }

    @Override
    public void onTokenDropped(int playerId, float x, float y) {
        // Anchoring needs a hit test against the AR frame, which only the GL thread can run.
        pendingTokenX = x;
        pendingTokenY = y;
        pendingTokenPlayerId = playerId;
    }

    // ---------------------------------------------------------------------------- the card list

    /**
     * Every known card as an openable detail list: scan thumbnail, name (with its owner in game
     * mode), type line and set when known, and whether it is tracking. Tapping a row places an
     * unlocated card (arming the existing surface-tap flow) or focuses a tracking one.
     */
    private void refreshCardList() {
        runOnUiThread(() -> {
            cardsToggle.setVisibility(cardOrder.isEmpty() ? View.GONE : View.VISIBLE);
            cardsToggle.setText(getString(R.string.ar_cards_toggle, cardOrder.size()));
            boolean open = cardListOpen && !cardOrder.isEmpty();
            cardListScroll.setVisibility(open ? View.VISIBLE : View.GONE);
            if (!open) {
                return;
            }

            cardList.removeAllViews();
            for (ActiveCard card : cardOrder) {
                View row = getLayoutInflater().inflate(R.layout.ar_card_row, cardList, false);

                ImageView thumb = row.findViewById(R.id.ar_row_thumb);
                thumb.setImageBitmap(card.displayBitmap);

                TextView title = row.findViewById(R.id.ar_row_title);
                GamePlayer owner = playersByCardKey.get(card.key);
                title.setText(owner == null ? card.name : owner.name + " · " + card.name);

                TextView subtitle = row.findViewById(R.id.ar_row_subtitle);
                String details = cardDetailsLine(card);
                subtitle.setText(details);
                subtitle.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);

                TextView status = row.findViewById(R.id.ar_row_status);
                status.setText(card.located
                        ? R.string.ar_card_tracking
                        : R.string.ar_card_tap_to_place);

                // A located card can be lifted for manual re-placement; an unlocated one already
                // re-places via the row tap. Commanders belong to their players, so scanned
                // table cards are the only removable ones.
                Button replace = row.findViewById(R.id.ar_row_replace);
                replace.setVisibility(card.located ? View.VISIBLE : View.GONE);
                replace.setOnClickListener(v -> rePlaceCard(card));

                Button remove = row.findViewById(R.id.ar_row_remove);
                remove.setVisibility(owner == null ? View.VISIBLE : View.GONE);
                remove.setOnClickListener(v -> removeCard(card));

                row.setOnClickListener(v -> onCardRowTapped(card));
                cardList.addView(row);
            }
        });
    }

    /** Type line and set when Scryfall told us; the keyword list as a fallback; else empty. */
    private String cardDetailsLine(ActiveCard card) {
        String details = card.typeLine;
        if (!card.setName.isEmpty()) {
            details = details.isEmpty() ? card.setName : details + " · " + card.setName;
        }
        if (details.isEmpty() && !card.keywords.isEmpty()) {
            details = String.join(", ", card.keywords);
        }
        return details;
    }

    private void onCardRowTapped(ActiveCard card) {
        cardListOpen = false;
        if (card.located) {
            overlay.setFocus(card.key);
            onFocusChanged(card.key);
        } else {
            pendingPlacementKey = card.key;
            notePlacementIntent();
        }
        refreshCardList();
        updateStatusLine();
    }

    /**
     * Lifts a card off wherever it sits and arms the surface-tap placement flow. Meant for a
     * manually placed card that landed on the wrong spot; a card the camera is actively
     * tracking will snap back the moment tracking re-locks — remove it instead if the
     * identification itself is wrong.
     */
    private void rePlaceCard(ActiveCard card) {
        synchronized (sessionLock) {
            if (card.anchor != null) {
                card.anchor.detach();
                card.anchor = null;
            }
            card.trackedImage = null;
        }
        card.located = false;
        pendingPlacementKey = card.key;
        notePlacementIntent();
        cardListOpen = false;
        refreshCardList();
        updateStatusLine();
    }

    /**
     * Removes a misidentified card everywhere it lives: the card set, the overlay, the
     * reference-image database, and the scanner's match memory — without that last step the
     * identifier would replay the match and the card would rejoin within a frame. The name
     * stays out only until Rescan; a removal is never permanent.
     */
    private void removeCard(ActiveCard card) {
        if (identifier != null) {
            identifier.dismiss(card.name);
        }
        cardsByKey.remove(card.key);
        cardOrder.remove(card);
        for (String printingId : card.printingUrls.keySet()) {
            cardByPrinting.remove(printingId);
        }
        overlay.removeCard(card.key);
        if (card.key.equals(pendingPlacementKey)) {
            pendingPlacementKey = null;
        }
        if (card.key.equals(focusedKey)) {
            focusedKey = null;
            overlay.setFocus(null);
            panel.setVisibility(View.GONE);
        }
        synchronized (sessionLock) {
            if (card.anchor != null) {
                card.anchor.detach();
                card.anchor = null;
            }
            card.trackedImage = null;
        }
        rebuildRequired = true;
        executor.execute(this::syncDatabase);
        refreshCardList();
        updateStatusLine();
    }

    private void updateStatusLine() {
        if (SystemClock.uptimeMillis() < statusFlashUntilMs) {
            return; // A "✓ card" flash is showing; the scheduled reset restores the line.
        }
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
        if (game != null) {
            GamePlayer player = focusedPlayer();
            if (player != null) {
                statusText.setText(card != null && playersByCardKey.get(card.key) == player
                        ? getString(R.string.ar_status_game_tracking, card.name, player.name)
                        // Selected via their life token, with no tracked commander to name.
                        : player.name);
            } else if (card != null) {
                // A scanned table card is focused; same wording as scan mode.
                statusText.setText(getString(R.string.ar_status_tracking, card.name));
            } else {
                // Nobody having a commander (chosen or AR-bound) changes the idle guidance:
                // there is nothing to point the camera at, but the tray tokens still work.
                int idle = playersByCardKey.isEmpty()
                        ? R.string.ar_status_game_no_commanders
                        : R.string.ar_status_game;
                statusText.setText(getString(guideMode ? R.string.ar_status_guide : idle));
            }
            return;
        }
        if (card != null) {
            statusText.setText(getString(R.string.ar_status_tracking, card.name));
            return;
        }
        statusText.setText(getString(
                guideMode ? R.string.ar_status_guide : R.string.ar_status_scan));
    }

    // ----------------------------------------------------------------------------- counter panel

    private CardCounters focusedCounters() {
        String key = focusedKey;
        if (key == null || isGameCard(key)) {
            // Commanders carry player life and tax, not per-printing counters; cards the
            // scanner found get counters in game mode too, exactly like scan mode.
            return null;
        }
        ActiveCard card = cardsByKey.get(key);
        return card == null ? null : store.get(card.printingId);
    }

    private GamePlayer focusedPlayer() {
        int playerId = focusedPlayerId;
        return game == null || playerId < 0 ? null : game.playerById(playerId);
    }

    /** Keeps plane finding hot for a while: call whenever a surface placement could follow. */
    private void notePlacementIntent() {
        lastPlacementIntentMs = SystemClock.elapsedRealtime();
    }

    /** The Motion pill: ambient animations on or off, remembered across sessions. */
    private void wireAnimationsToggle() {
        Button button = findViewById(R.id.ar_anim);
        animationsEnabled = getPreferences(MODE_PRIVATE).getBoolean(PREF_ANIMATIONS, true);
        applyAnimationsPill(button);
        button.setOnClickListener(v -> {
            animationsEnabled = !animationsEnabled;
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(PREF_ANIMATIONS, animationsEnabled).apply();
            applyAnimationsPill(button);
        });
    }

    /** Label and dimming agree on the state, so "on/off" cannot read as the tap's action. */
    private void applyAnimationsPill(Button button) {
        button.setText(animationsEnabled ? R.string.ar_anim_on : R.string.ar_anim_off);
        button.setAlpha(animationsEnabled ? 1f : 0.45f);
    }

    private void wireCounterControls() {
        findViewById(R.id.ar_close).setOnClickListener(v -> finish());
        wireStatButton(R.id.ar_stat_pp, 1, 1);
        wireStatButton(R.id.ar_stat_mm, -1, -1);
        wireStatButton(R.id.ar_stat_pm, 1, -1);
        wireStatButton(R.id.ar_stat_mp, -1, 1);
        findViewById(R.id.ar_stat_custom).setOnClickListener(v -> showCustomStatDialog());
        findViewById(R.id.ar_stat_reset).setOnClickListener(v -> {
            CardCounters counters = focusedCounters();
            if (counters == null || counters.stats.isEmpty()) {
                return;
            }
            counters.clearStats();
            persistAndRefresh();
        });
        findViewById(R.id.ar_keyword).setOnClickListener(v ->
                showKeywordWheel(overlay.getWidth() / 2f, overlay.getHeight() / 2f, false));
        findViewById(R.id.ar_set_commander).setOnClickListener(v -> {
            ActiveCard card = focusedKey == null ? null : cardsByKey.get(focusedKey);
            if (game != null && card != null && !isGameCard(card.key)) {
                showBindCommanderDialog(card);
            }
        });
        findViewById(R.id.ar_tax_minus).setOnClickListener(v -> adjustCommanderCasts(-1));
        findViewById(R.id.ar_tax_plus).setOnClickListener(v -> adjustCommanderCasts(1));
        findViewById(R.id.ar_life_minus).setOnClickListener(v -> adjustGameLife(-1));
        findViewById(R.id.ar_life_plus).setOnClickListener(v -> adjustGameLife(1));
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

    /** The tax stepper serves whichever panel is showing: a player's casts or card counters. */
    private void adjustCommanderCasts(int delta) {
        CardCounters counters = focusedCounters();
        if (counters != null) {
            counters.commanderCasts = Math.max(0, counters.commanderCasts + delta);
            persistAndRefresh();
            return;
        }
        GamePlayer player = focusedPlayer();
        if (player == null) {
            return;
        }
        player.adjustCasts(delta);
        publishGameResult();
        refreshGameUi();
    }

    private void adjustGameLife(int delta) {
        GamePlayer player = focusedPlayer();
        if (game == null || player == null) {
            return;
        }
        player.adjustLife(delta);
        publishGameResult();
        refreshGameUi();
    }

    /**
     * Stamps the latest game state onto the activity result. Called after every mutation, so
     * any way this screen closes returns the freshest values; a serialisation failure keeps the
     * previously published result rather than none at all.
     */
    private void publishGameResult() {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_GAME_RESULT, game.toJsonString());
            setResult(RESULT_OK, data);
        } catch (JSONException e) {
            Log.w(TAG, "Could not serialise the game result.", e);
        }
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

    /**
     * Opens the radial keyword picker around (x, y). Presets already on the card as counters
     * come up highlighted (selecting removes them); printed keywords come up muted and inert.
     * With {@code midGesture} the opening long-press still owns the touch stream, so the
     * overlay relays it to the wheel for drag-and-release selection.
     */
    private void showKeywordWheel(float x, float y, boolean midGesture) {
        List<KeywordWheelView.Entry> entries = buildWheelEntries();
        if (entries.isEmpty()) {
            return; // Commanders carry player life, not counters — no wheel for them.
        }
        keywordWheel.show(x, y, entries, midGesture);
        if (keywordWheel.isShowing()) {
            // Only relay touches once the wheel is really up — a bailed show() with the relay
            // set would swallow every touch into an invisible view.
            overlay.setTouchRelay(keywordWheel);
            keywordWheelOpen = true;
            overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /** The wheel's entries for the focused card, or empty when nothing focusable holds counters. */
    private List<KeywordWheelView.Entry> buildWheelEntries() {
        CardCounters counters = focusedCounters();
        ActiveCard card = focusedKey == null ? null : cardsByKey.get(focusedKey);
        List<KeywordWheelView.Entry> entries = new ArrayList<>();
        if (counters == null || card == null) {
            return entries;
        }
        for (String preset : getResources().getStringArray(R.array.ar_keyword_presets)) {
            entries.add(new KeywordWheelView.Entry(preset,
                    containsIgnoreCase(counters.keywords, preset),
                    containsIgnoreCase(card.keywords, preset),
                    false));
        }
        entries.add(new KeywordWheelView.Entry(
                getString(R.string.ar_keyword_custom), false, false, true));
        return entries;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String item : list) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** Deprecated in favour of predictive back, which this plain Activity does not opt into. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (keywordWheel.isShowing()) {
            keywordWheel.hide();
            return;
        }
        super.onBackPressed();
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
        // A keyword counter may have granted or removed Flying/Reach just now.
        ActiveCard focused = focusedKey == null ? null : cardsByKey.get(focusedKey);
        if (focused != null) {
            updateAbilityFlags(focused);
        }
        refreshCounterUi();
    }

    /**
     * Re-derives the render-affecting abilities from printed keywords plus keyword counters.
     * UI thread only — the counter store is not GL-safe; the GL thread reads the flags.
     */
    private void updateAbilityFlags(ActiveCard card) {
        CardCounters counters = store.get(card.printingId);
        card.flying = hasAbility(card, counters, "Flying");
        card.reach = hasAbility(card, counters, "Reach");
    }

    private static boolean hasAbility(ActiveCard card, CardCounters counters, String ability) {
        for (String keyword : card.keywords) {
            if (keyword.equalsIgnoreCase(ability)) {
                return true;
            }
        }
        for (String keyword : counters.keywords) {
            if (keyword.equalsIgnoreCase(ability)) {
                return true;
            }
        }
        return false;
    }

    /** Rebuilds the focused card's tappable chip row and re-stamps its overlay chips. */
    private void refreshCounterUi() {
        // The panel follows the focused target: a player (via commander or token) gets the
        // life/tax rows, a scanned table card gets the normal counter controls plus the
        // option to become someone's commander.
        boolean gameTarget = game != null && focusedPlayerId >= 0;
        applyPanelRows(gameTarget);
        if (gameTarget) {
            refreshGameUi();
            return;
        }
        CardCounters counters = focusedCounters();
        chipRow.removeAllViews();
        ActiveCard card = focusedKey == null ? null : cardsByKey.get(focusedKey);
        setPanelTitle(card == null ? null : card.name);
        if (counters == null) {
            return;
        }

        // The card's own keywords first: tap for the glossary definition. They are part of the
        // card, so there is nothing to remove.
        if (card != null) {
            for (String keyword : card.keywords) {
                addChip(keyword, () -> showKeywordDefinition(keyword, null));
            }
        }

        for (String keyword : new ArrayList<>(counters.keywords)) {
            addChip(keyword + " ◆",
                    () -> showKeywordDefinition(keyword, () -> {
                        counters.removeKeyword(keyword);
                        persistAndRefresh();
                    }));
        }
        for (CardCounters.StatCounter stat : new ArrayList<>(counters.stats)) {
            String label = stat.count == 1 ? stat.label() : stat.label() + " ×" + stat.count;
            addChip(label + " ✕", () -> {
                counters.removeStat(stat.power, stat.toughness);
                persistAndRefresh();
            });
        }
        taxLabel.setText(getString(R.string.ar_tax_chip, counters.commanderTax()));

        if (card != null) {
            pushCounterChips(card);
        }
    }

    /**
     * Collapsed, the panel is its header plus the essential row — the life stepper for a
     * player, the counter chips for a card — keeping the camera view clear on small screens.
     * Expanded (via the header) it adds the stat buttons and the keyword/commander/tax row.
     */
    private void applyPanelRows(boolean gameTarget) {
        panelExpandChevron.setText(panelExpanded
                ? R.string.ar_panel_collapse : R.string.ar_panel_expand);
        lifeRow.setVisibility(gameTarget ? View.VISIBLE : View.GONE);
        chipScroll.setVisibility(gameTarget && !panelExpanded ? View.GONE : View.VISIBLE);
        statRow.setVisibility(!gameTarget && panelExpanded ? View.VISIBLE : View.GONE);
        actionRow.setVisibility(panelExpanded ? View.VISIBLE : View.GONE);
        keywordButton.setVisibility(gameTarget ? View.GONE : View.VISIBLE);
        setCommanderButton.setVisibility(
                game != null && !gameTarget ? View.VISIBLE : View.GONE);
    }

    /** Picks which player the focused scanned card belongs to as their commander. */
    private void showBindCommanderDialog(ActiveCard card) {
        List<GamePlayer> players = game.players();
        String[] names = new String[players.size()];
        for (int i = 0; i < players.size(); i++) {
            names[i] = players.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ar_bind_commander_title, card.name))
                .setItems(names, (dialog, which) -> bindCommander(card, players.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Binds a scanned table card to a player as their commander, whenever the user wants —
     * commanders no longer have to be chosen before the game reaches AR. The card's badge
     * becomes the player's (life, tax, reminders), the player's life token takes its art, and
     * any earlier binding for that player reverts to a plain counter-carrying table card.
     * Session-local: the web merges back only life and casts, so the game model is untouched.
     */
    private void bindCommander(ActiveCard card, GamePlayer player) {
        for (Map.Entry<String, GamePlayer> entry : playersByCardKey.entrySet()) {
            if (entry.getValue() == player && !entry.getKey().equals(card.key)) {
                playersByCardKey.remove(entry.getKey());
                ActiveCard previous = cardsByKey.get(entry.getKey());
                if (previous != null) {
                    pushCounterChips(previous);
                }
            }
        }
        playersByCardKey.put(card.key, player);
        focusedPlayerId = player.id;
        overlay.setFocusedToken(player.id);

        overlay.upsertToken(player.id, tokenName(player), card.displayBitmap, player.life,
                player.commanderTax());
        pushGameChips(card, player);
        refreshCounterUi();
        refreshCardList();
        updateStatusLine();
    }

    /**
     * The glossary popup: what a keyword does, with "Remove one" offered only for keyword
     * counters — a card's printed keywords are not removable.
     */
    private void showKeywordDefinition(String keyword, Runnable onRemove) {
        String definition = glossary.lookup(keyword);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(keyword)
                .setMessage(definition != null ? definition : getString(R.string.ar_no_definition))
                .setPositiveButton(android.R.string.ok, null);
        if (onRemove != null) {
            builder.setNegativeButton(R.string.ar_remove_one, (dialog, which) -> onRemove.run());
        }
        builder.show();
    }

    /** Game-mode panel: the focused player's life and tax under their commander's badge. */
    private void refreshGameUi() {
        chipRow.removeAllViews();
        GamePlayer player = focusedPlayer();
        setPanelTitle(player == null ? null : tokenName(player));
        if (player == null) {
            return;
        }
        lifeLabel.setText(getString(R.string.ar_life_chip, player.life));
        taxLabel.setText(getString(R.string.ar_tax_chip, player.commanderTax()));
        overlay.upsertToken(player.id, tokenName(player), null, player.life,
                player.commanderTax());
        // Reminders are read-only here — tapping one just shows it in full, uncut.
        for (String reminder : player.reminders) {
            addChip(reminder, () -> new AlertDialog.Builder(this)
                    .setTitle(player.name)
                    .setMessage(reminder)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }
        ActiveCard card = focusedKey == null ? null : cardsByKey.get(focusedKey);
        if (card != null && playersByCardKey.get(card.key) == player) {
            pushGameChips(card, player);
        }
    }

    /**
     * The badge over a commander: owner's name (turn-marked when active), then life, tax when
     * owed, and any reminders anchored to this player's turn.
     */
    private void pushGameChips(ActiveCard card, GamePlayer player) {
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.ar_life_chip, player.life));
        if (player.commanderCasts > 0) {
            labels.add(getString(R.string.ar_tax_chip, player.commanderTax()));
        }
        labels.addAll(player.reminders);
        overlay.setChips(card.key, labels, tokenName(player));
    }

    /** The player's name with a turn marker when it is their turn. */
    private static String tokenName(GamePlayer player) {
        return player.active ? "▶ " + player.name : player.name;
    }

    /**
     * Sends one card's counter chips to the overlay, where they render above the card. Stat
     * counters collapse into the single net summary chip — per-kind chips live only in the
     * panel, where they are removal controls — so the row stays short on a busy table.
     */
    private void pushCounterChips(ActiveCard card) {
        CardCounters counters = store.get(card.printingId);
        List<String> labels = new ArrayList<>(counters.keywords);
        if (counters.commanderCasts > 0) {
            labels.add(getString(R.string.ar_tax_chip, counters.commanderTax()));
        }
        String summary = counters.stats.isEmpty()
                ? ""
                : String.format("%+d/%+d", counters.netPower(), counters.netToughness());
        overlay.setChips(card.key, labels, summary);
    }

    private void setPanelTitle(String title) {
        if (title == null || title.isEmpty()) {
            // Invisible, not gone: the header keeps its height and the chevron stays put.
            panelTitle.setVisibility(View.INVISIBLE);
        } else {
            panelTitle.setText(title);
            panelTitle.setVisibility(View.VISIBLE);
        }
    }

    private void addChip(String label, Runnable onTap) {
        // The style rides in as defStyleRes — a plain new Button() would be a full-size
        // default-themed Material button, far too heavy for a counter chip.
        Button chip = new Button(this, null, 0, R.style.ArChip);
        chip.setText(label);
        chip.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd((int) (6 * getResources().getDisplayMetrics().density));
        chipRow.addView(chip, params);
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
                if (identifier != null && !keywordWheelOpen) {
                    identifier.maybeIdentify(frame);
                }
                if (frameRecorder != null) {
                    frameRecorder.maybeCapture(frame);
                }
                updateTrackedImages(frame);
                handlePendingTap(frame);
                handlePendingTokenDrop(frame);
                updatePlaneFinding();
                overlay.postGeometry(computePoses(camera), computeTokenPoses(camera));
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
                    // Show the printing that is actually on the table. Its scan is on disk from
                    // registration; the decode runs off this GL thread.
                    String recognisedId = image.getName();
                    executor.execute(() -> {
                        Bitmap recognised = decodePrinting(card, recognisedId);
                        if (recognised != null) {
                            card.displayBitmap = recognised;
                            overlay.upsertCard(card.key, recognised);
                            refreshCardList();
                        }
                    });
                }
                // Commanders skip this: their counters belong to the player, not the printing.
                if (!isGameCard(card.key) && !image.getName().equals(card.printingId)) {
                    // A different printing of this card: its own counters take over.
                    card.printingId = image.getName();
                    runOnUiThread(() -> {
                        updateAbilityFlags(card);
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
                if (game != null) {
                    GamePlayer player = playersByCardKey.get(card.key);
                    focusedPlayerId = player != null ? player.id : -1;
                    overlay.setFocusedToken(focusedPlayerId);
                }
            }
            runOnUiThread(() -> {
                if (card.key.equals(focusedKey)) {
                    panel.setVisibility(View.VISIBLE);
                    refreshCounterUi();
                }
                refreshCardList();
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

        /**
         * Drops a dragged life token onto the table: a hit test where the finger let go, an
         * anchor on success (replacing any previous spot), back to the tray on a miss.
         */
        private void handlePendingTokenDrop(Frame frame) {
            int playerId = pendingTokenPlayerId;
            if (playerId < 0) {
                return;
            }
            pendingTokenPlayerId = -1;

            for (HitResult hit : frame.hitTest(pendingTokenX, pendingTokenY)) {
                Trackable trackable = hit.getTrackable();
                boolean usable = (trackable instanceof Plane
                        && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
                        || trackable instanceof Point;
                if (usable) {
                    Anchor previous = tokenAnchors.put(playerId, hit.createAnchor());
                    if (previous != null) {
                        previous.detach();
                    }
                    overlay.setTokenPlaced(playerId, true);
                    return;
                }
            }

            // No surface there: the token goes back to the tray, and any old anchor dies with
            // the spot it marked.
            Anchor orphan = tokenAnchors.remove(playerId);
            if (orphan != null) {
                orphan.detach();
            }
            overlay.setTokenPlaced(playerId, false);
            runOnUiThread(() -> Toast.makeText(ArCardActivity.this,
                    R.string.ar_token_place_failed, Toast.LENGTH_SHORT).show());
        }

        /** Screen positions for the anchored life tokens, sized by their projected width. */
        private List<CardOverlayView.TokenPose> computeTokenPoses(Camera camera) {
            List<CardOverlayView.TokenPose> result = new ArrayList<>();
            if (tokenAnchors.isEmpty() || camera.getTrackingState() != TrackingState.TRACKING) {
                return result;
            }

            for (Map.Entry<Integer, Anchor> entry : tokenAnchors.entrySet()) {
                Anchor anchor = entry.getValue();
                if (anchor.getTrackingState() != TrackingState.TRACKING) {
                    continue;
                }
                Pose pose = anchor.getPose();
                float[] center = projectToScreen(new float[] {pose.tx(), pose.ty(), pose.tz()});
                if (center == null) {
                    continue;
                }
                float[] edge = projectToScreen(
                        pose.transformPoint(new float[] {TOKEN_WIDTH_M / 2f, 0, 0}));
                if (edge == null) {
                    continue;
                }
                float widthPx = 2f * (float) Math.hypot(
                        edge[0] - center[0], edge[1] - center[1]);
                result.add(new CardOverlayView.TokenPose(
                        entry.getKey(), center[0], center[1], widthPx));
            }
            return result;
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
            // A Flying card renders lifted along WORLD up (gravity), not the pose's own up
            // axis: a feature-point anchor's basis can face the camera, which would turn the
            // lift into a pure move-closer with no visible altitude.
            boolean flying = card.flying;
            float lift = flying ? flyLift() : 0f;
            float[] corners = projectCorners(pose, halfWidth, halfHeight, lift);
            if (corners == null) {
                return null;
            }
            float[] center = projectToScreen(
                    new float[] {pose.tx(), pose.ty() + lift, pose.tz()});
            if (center == null) {
                return null;
            }

            float baseWidth = (float) Math.hypot(corners[2] - corners[0], corners[3] - corners[1]);
            CardOverlayView.CardPose out = new CardOverlayView.CardPose(
                    card.key, corners, center[0], center[1], baseWidth);
            if (flying) {
                // The ground shadow needs the table-level geometry too. Losing it to a
                // projection edge case loses only the shadow, never the card itself.
                float[] ground = projectToScreen(pose.transformPoint(
                        new float[] {SHADOW_OFFSET_M, 0, SHADOW_OFFSET_M}));
                float[] shadowCorners = projectShadowCorners(pose, halfWidth, halfHeight);
                if (ground != null && shadowCorners != null) {
                    out.flying = true;
                    out.shadowCorners = shadowCorners;
                    out.groundX = ground[0];
                    out.groundY = ground[1];
                }
            } else if (card.reach) {
                float[] sky = projectToScreen(
                        new float[] {pose.tx(), pose.ty() + FLY_HEIGHT_M, pose.tz()});
                if (sky != null) {
                    out.reach = true;
                    out.skyX = sky[0];
                    out.skyY = sky[1];
                }
            }
            return out;
        }

        /**
         * GL thread, every frame: turns plane finding off once no placement is plausible and
         * back on when one is. Hit tests fall back to feature points either way, so a placement
         * during the re-finding beat degrades instead of failing. The reconfigure runs on the
         * database executor so it serialises with database rebuilds touching the same session.
         */
        private void updatePlaneFinding() {
            boolean want = pendingPlacementKey != null
                    || SystemClock.elapsedRealtime() - lastPlacementIntentMs < PLANE_IDLE_AFTER_MS
                    || anyCardUnlocated();
            if (want == planeFindingOn) {
                return;
            }
            planeFindingOn = want;
            executor.execute(() -> {
                synchronized (sessionLock) {
                    if (session != null) {
                        session.configure(buildConfig(database));
                    }
                }
            });
        }

        private boolean anyCardUnlocated() {
            for (ActiveCard card : cardOrder) {
                if (!card.located) {
                    return true;
                }
            }
            return false;
        }

        /** The altitude of a flyer right now: base height plus a slow sine bob, unless the
         *  Motion toggle stilled it — then the static lift (and offset shadow) carry it. */
        private float flyLift() {
            if (!animationsEnabled) {
                return FLY_HEIGHT_M;
            }
            double phase = (SystemClock.uptimeMillis() % FLY_BOB_PERIOD_MS)
                    / (double) FLY_BOB_PERIOD_MS;
            return FLY_HEIGHT_M + FLY_BOB_M * (float) Math.sin(phase * 2 * Math.PI);
        }

        /** The card's four corners lifted the given height along WORLD up, projected to screen.
         *  The image lies in the pose's X-Z plane; -Z is the top edge of the artwork. */
        private float[] projectCorners(Pose pose, float halfWidth, float halfHeight, float lift) {
            return projectQuad(pose, lift, new float[][] {
                    {-halfWidth, 0, -halfHeight},
                    {halfWidth, 0, -halfHeight},
                    {halfWidth, 0, halfHeight},
                    {-halfWidth, 0, halfHeight},
            });
        }

        /** A flyer's shadow quad: table level, pushed out by the faked sun angle, shrunken. */
        private float[] projectShadowCorners(Pose pose, float halfWidth, float halfHeight) {
            float w = halfWidth * SHADOW_SCALE;
            float h = halfHeight * SHADOW_SCALE;
            return projectQuad(pose, 0f, new float[][] {
                    {-w + SHADOW_OFFSET_M, 0, -h + SHADOW_OFFSET_M},
                    {w + SHADOW_OFFSET_M, 0, -h + SHADOW_OFFSET_M},
                    {w + SHADOW_OFFSET_M, 0, h + SHADOW_OFFSET_M},
                    {-w + SHADOW_OFFSET_M, 0, h + SHADOW_OFFSET_M},
            });
        }

        /** Projects pose-local corners raised {@code worldLift} metres along gravity-up. */
        private float[] projectQuad(Pose pose, float worldLift, float[][] localCorners) {
            float[] corners = new float[8];
            for (int i = 0; i < 4; i++) {
                float[] world = pose.transformPoint(localCorners[i]);
                world[1] += worldLift;
                float[] screen = projectToScreen(world);
                if (screen == null) {
                    return null;
                }
                corners[i * 2] = screen[0];
                corners[i * 2 + 1] = screen[1];
            }
            return corners;
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
