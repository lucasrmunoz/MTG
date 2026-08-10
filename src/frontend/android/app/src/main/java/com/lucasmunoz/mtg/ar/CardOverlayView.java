package com.lucasmunoz.mtg.ar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Draws every active card over the camera view at once: each floats upright above its physical
 * card or lies perspective-warped on top of it, carrying its counter chips.
 *
 * Geometry arrives from the GL thread once per frame as a list of {@link CardPose}; visual state
 * per card — scan bitmap, chips, floating-vs-placed, pinch scale — lives here, keyed by card.
 * One card is focused at a time: tapping a card focuses it, tapping the focused card toggles it
 * between floating and placed, and pinching zooms it. Screen-space rendering from projected
 * poses keeps all of this free of a 3D scene graph.
 */
public final class CardOverlayView extends View {

    /** Where one card sits on screen this frame, as computed by the AR thread. */
    static final class CardPose {
        final String key;
        /** Projected physical-card corners: TL, TR, BR, BL as x,y pairs. */
        final float[] corners;
        final float centerX;
        final float centerY;
        /** Projected width of the physical card in pixels, the basis for floating size. */
        final float baseWidthPx;

        /** Table-level corners when the card draws elevated (Flying); null otherwise. */
        float[] shadowCorners;
        /** Table-level centre — the foot of the altitude line — when flying. */
        float groundX;
        float groundY;
        /** The flyer-altitude point above the card: the tip of the Reach indicator. */
        float skyX;
        float skyY;
        boolean flying;
        boolean reach;

        CardPose(String key, float[] corners, float centerX, float centerY, float baseWidthPx) {
            this.key = key;
            this.corners = corners;
            this.centerX = centerX;
            this.centerY = centerY;
            this.baseWidthPx = baseWidthPx;
        }
    }

    /** Where one placed life token sits on screen this frame, projected from its anchor. */
    static final class TokenPose {
        final int playerId;
        final float centerX;
        final float centerY;
        final float widthPx;

        TokenPose(int playerId, float centerX, float centerY, float widthPx) {
            this.playerId = playerId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.widthPx = widthPx;
        }
    }

    interface Listener {
        /** A tap focused a different card; the counter panel should follow. */
        void onFocusChanged(String key);

        /** A tap hit no card: a chance to place a pending card at this screen point. */
        void onTapEmpty(float x, float y);

        /** Game mode: a life token was tapped — its player should become the focused one. */
        default void onTokenTapped(int playerId) {}

        /** Game mode: a dragged life token was released here — try to anchor it to the table. */
        default void onTokenDropped(int playerId, float x, float y) {}
    }

    /**
     * One player's life token: their commander's art with the life total, living in the tray
     * until a drag anchors it to the table. Values are owned by the UI thread; `placed` is
     * confirmed by the activity once a world anchor exists.
     */
    private static final class TokenState {
        final int playerId;
        String name = "";
        Bitmap art;
        int life;
        int tax;
        boolean placed;
        boolean dragging;
        float dragX;
        float dragY;
        /** Where the token was last drawn, for hit-testing taps and drag starts. */
        final RectF lastRect = new RectF();

        TokenState(int playerId) {
            this.playerId = playerId;
        }
    }

    /** Per-card visual state, owned by this view and mutated from the UI thread. */
    private static final class RenderState {
        Bitmap bitmap;
        final List<String> chips = new ArrayList<>();
        String summary = "";
        boolean placed;
        float scale = 1f;
        /** Where the card was last drawn, for routing taps. */
        final RectF lastRect = new RectF();
    }

    private final Map<String, RenderState> renders = new ConcurrentHashMap<>();
    private volatile List<CardPose> poses = Collections.emptyList();
    private volatile List<TokenPose> tokenPoses = Collections.emptyList();
    private volatile String focusedKey;
    private Listener listener;

    private final Map<Integer, TokenState> tokens = new ConcurrentHashMap<>();
    /** Draw/tray order for tokens, by player id, so the tray is stable. */
    private final List<TokenState> tokenOrder = new CopyOnWriteArrayList<>();
    private volatile int focusedTokenId = -1;
    private TokenState draggingToken;
    private float dragDownX;
    private float dragDownY;
    private boolean dragMoved;
    private final int touchSlop;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint chipBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusedBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tokenScrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tokenSurface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tokenLifeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tokenSmallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The dark footprint a Flying card casts at table level. */
    private final Paint shadowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Dashed vertical: a flyer's tether to its shadow, or a Reach card's grasp upward. */
    private final Paint altitudePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shadowPath = new Path();
    private final Path altitudePath = new Path();

    /** The grey card-aspect outline of guide-box scanning; null when ambient scanning. */
    private volatile float[] guideBox;
    /** While now is before this, the outline draws green: card text is being read in the box. */
    private volatile long guideActiveUntilMs;
    private static final long GUIDE_ACTIVE_TTL_MS = 900;
    private final RectF guideRect = new RectF();
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix placeMatrix = new Matrix();
    private final RectF tokenRect = new RectF();
    private final Rect tokenSrcRect = new Rect();
    private final Path tokenClip = new Path();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    public CardOverlayView(Context context) {
        this(context, null);
    }

    /** Inflated from activity_ar_card.xml, which uses this constructor. */
    public CardOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        chipBackground.setColor(Color.argb(230, 26, 26, 46));
        chipHighlight.setColor(Color.argb(230, 230, 126, 34));
        chipText.setColor(Color.WHITE);
        chipText.setTextSize(dp(13));
        focusedBorder.setStyle(Paint.Style.STROKE);
        focusedBorder.setStrokeWidth(dp(2.5f));
        focusedBorder.setColor(Color.argb(220, 230, 126, 34));
        idleBorder.setStyle(Paint.Style.STROKE);
        idleBorder.setStrokeWidth(dp(1.5f));
        idleBorder.setColor(Color.argb(160, 155, 89, 182));
        tokenScrim.setColor(Color.argb(110, 0, 0, 0));
        tokenSurface.setColor(Color.argb(235, 45, 45, 68));
        tokenLifeText.setColor(Color.WHITE);
        tokenLifeText.setTextAlign(Paint.Align.CENTER);
        tokenLifeText.setFakeBoldText(true);
        tokenLifeText.setShadowLayer(dp(2), 0, dp(1), Color.argb(200, 0, 0, 0));
        tokenSmallText.setColor(Color.WHITE);
        tokenSmallText.setTextAlign(Paint.Align.CENTER);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(2.5f));
        guidePaint.setColor(Color.argb(220, 189, 195, 199));
        guideActivePaint.setStyle(Paint.Style.STROKE);
        guideActivePaint.setStrokeWidth(dp(3f));
        guideActivePaint.setColor(Color.argb(220, 46, 204, 113));
        shadowFill.setStyle(Paint.Style.FILL);
        shadowFill.setColor(Color.argb(80, 0, 0, 0));
        altitudePaint.setStyle(Paint.Style.STROKE);
        altitudePaint.setStrokeWidth(dp(2));
        altitudePaint.setColor(Color.argb(200, 46, 204, 113));
        altitudePaint.setPathEffect(new DashPathEffect(new float[] {dp(6), dp(5)}, 0));
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        RenderState focused = focusedKey == null ? null : renders.get(focusedKey);
                        if (focused != null) {
                            focused.scale =
                                    clamp(focused.scale * detector.getScaleFactor(), 0.35f, 3.5f);
                            invalidate();
                        }
                        return true;
                    }
                });

        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                handleTap(event.getX(), event.getY());
                return true;
            }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Creates or updates a card's scan; a null bitmap registers the card before art arrives. */
    void upsertCard(String key, Bitmap bitmap) {
        RenderState state = stateFor(key);
        if (bitmap != null) {
            state.bitmap = bitmap;
        }
        postInvalidate();
    }

    /** Called from the UI thread whenever one card's counters change. */
    void setChips(String key, List<String> labels, String summary) {
        RenderState state = stateFor(key);
        state.chips.clear();
        state.chips.addAll(labels);
        state.summary = summary;
        postInvalidate();
    }

    void setFocus(String key) {
        focusedKey = key;
        postInvalidate();
    }

    /** Drops a card's visual state entirely; its poses stop arriving once the card is gone. */
    void removeCard(String key) {
        renders.remove(key);
        if (key.equals(focusedKey)) {
            focusedKey = null;
        }
        postInvalidate();
    }

    /**
     * Creates or updates a player's life token. A null art bitmap keeps whatever art the token
     * already has, mirroring {@link #upsertCard}; life, tax and name always update.
     */
    void upsertToken(int playerId, String name, Bitmap art, int life, int tax) {
        TokenState token = tokens.get(playerId);
        if (token == null) {
            token = new TokenState(playerId);
            tokens.put(playerId, token);
            tokenOrder.add(token);
        }
        token.name = name;
        if (art != null) {
            token.art = art;
        }
        token.life = life;
        token.tax = tax;
        postInvalidate();
    }

    /** The activity confirms whether a dropped token got a world anchor or returns to the tray. */
    void setTokenPlaced(int playerId, boolean placed) {
        TokenState token = tokens.get(playerId);
        if (token != null) {
            token.placed = placed;
            postInvalidate();
        }
    }

    void setFocusedToken(int playerId) {
        focusedTokenId = playerId;
        postInvalidate();
    }

    /** Called from the GL thread once per rendered frame. */
    void postGeometry(List<CardPose> newPoses, List<TokenPose> newTokenPoses) {
        poses = newPoses;
        tokenPoses = newTokenPoses;
        postInvalidate();
    }

    /** Shows the grey guide outline at these view coords {l, t, r, b}; null hides it. */
    void setGuideBox(float[] box) {
        guideBox = box;
        guideActiveUntilMs = 0;
        postInvalidate();
    }

    /** Card text was just read inside the outline — draw it green for a beat, Mythic-style. */
    void markGuideBoxActive() {
        guideActiveUntilMs = SystemClock.elapsedRealtime() + GUIDE_ACTIVE_TTL_MS;
        postInvalidate();
    }

    private RenderState stateFor(String key) {
        RenderState state = renders.get(key);
        if (state == null) {
            state = new RenderState();
            renders.put(key, state);
        }
        return state;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Tokens take priority over card gestures: a touch starting on one becomes a drag (or a
        // tap when the finger never really moves), and the pinch/tap detectors sit that one out.
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                TokenState token = tokenAt(event.getX(), event.getY());
                if (token != null) {
                    draggingToken = token;
                    token.dragging = true;
                    token.dragX = event.getX();
                    token.dragY = event.getY();
                    dragDownX = event.getX();
                    dragDownY = event.getY();
                    dragMoved = false;
                    invalidate();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (draggingToken != null) {
                    draggingToken.dragX = event.getX();
                    draggingToken.dragY = event.getY();
                    if (Math.hypot(event.getX() - dragDownX, event.getY() - dragDownY)
                            > touchSlop) {
                        dragMoved = true;
                    }
                    invalidate();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (draggingToken != null) {
                    TokenState token = draggingToken;
                    draggingToken = null;
                    token.dragging = false;
                    if (dragMoved) {
                        if (listener != null) {
                            listener.onTokenDropped(token.playerId, event.getX(), event.getY());
                        }
                    } else if (listener != null) {
                        listener.onTokenTapped(token.playerId);
                    }
                    invalidate();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (draggingToken != null) {
                    draggingToken.dragging = false;
                    draggingToken = null;
                    invalidate();
                    return true;
                }
                break;
            }
            default:
                break;
        }
        if (draggingToken != null) {
            return true;
        }

        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        return true;
    }

    /** The topmost token under a point; tray and placed tokens alike. */
    private TokenState tokenAt(float x, float y) {
        for (int i = tokenOrder.size() - 1; i >= 0; i--) {
            TokenState token = tokenOrder.get(i);
            if (token.lastRect.contains(x, y)) {
                return token;
            }
        }
        return null;
    }

    /** Focus the tapped card, toggle it when already focused, or offer the point for placement. */
    private void handleTap(float x, float y) {
        for (CardPose pose : poses) {
            RenderState state = renders.get(pose.key);
            if (state == null || state.bitmap == null || !state.lastRect.contains(x, y)) {
                continue;
            }
            if (pose.key.equals(focusedKey)) {
                state.placed = !state.placed;
                invalidate();
            } else {
                focusedKey = pose.key;
                invalidate();
                if (listener != null) {
                    listener.onFocusChanged(pose.key);
                }
            }
            return;
        }
        if (listener != null) {
            listener.onTapEmpty(x, y);
        }
    }

    /** Fixed on-screen size for tray and dragging tokens; placed ones size by projection. */
    private static final float TOKEN_TRAY_DP = 68f;
    private static final float TOKEN_MIN_DP = 44f;
    private static final float TOKEN_MAX_DP = 160f;

    @Override
    protected void onDraw(Canvas canvas) {
        float[] guide = guideBox;
        if (guide != null) {
            guideRect.set(guide[0], guide[1], guide[2], guide[3]);
            boolean active = SystemClock.elapsedRealtime() < guideActiveUntilMs;
            canvas.drawRoundRect(guideRect, dp(12), dp(12),
                    active ? guideActivePaint : guidePaint);
        }
        for (CardPose pose : poses) {
            RenderState state = renders.get(pose.key);
            if (state == null || state.bitmap == null) {
                continue;
            }
            if (pose.flying) {
                drawGroundShadow(canvas, pose);
            }
            if (state.placed) {
                drawPlaced(canvas, pose, state);
            } else {
                drawFloating(canvas, pose, state);
            }
            if (pose.reach) {
                drawReachIndicator(canvas, pose);
            }
        }
        drawTokens(canvas);
    }

    /**
     * Life tokens draw above everything: placed ones at their anchor's projected position, the
     * rest in a tray row near the top, and the dragged one under the finger. A placed token
     * whose anchor is off-screen this frame is not drawn and loses its touch rect — invisible
     * things must not catch taps.
     */
    private void drawTokens(Canvas canvas) {
        List<TokenPose> projected = tokenPoses;
        float trayX = dp(8);
        float trayY = dp(96);

        for (TokenState token : tokenOrder) {
            if (token.dragging) {
                drawToken(canvas, token, token.dragX, token.dragY, dp(TOKEN_TRAY_DP));
                continue;
            }
            if (token.placed) {
                TokenPose pose = null;
                for (TokenPose candidate : projected) {
                    if (candidate.playerId == token.playerId) {
                        pose = candidate;
                        break;
                    }
                }
                if (pose != null) {
                    float width = clamp(pose.widthPx, dp(TOKEN_MIN_DP), dp(TOKEN_MAX_DP));
                    drawToken(canvas, token, pose.centerX, pose.centerY, width);
                } else {
                    token.lastRect.setEmpty();
                }
                continue;
            }
            float half = dp(TOKEN_TRAY_DP) / 2f;
            drawToken(canvas, token, trayX + half, trayY + half, dp(TOKEN_TRAY_DP));
            trayX += dp(TOKEN_TRAY_DP) + dp(8);
        }
    }

    /** One rounded-square token: commander art (or surface colour), life, name, tax. */
    private void drawToken(Canvas canvas, TokenState token, float cx, float cy, float width) {
        float half = width / 2f;
        tokenRect.set(cx - half, cy - half, cx + half, cy + half);
        token.lastRect.set(tokenRect);
        float corner = dp(10);

        canvas.save();
        tokenClip.rewind();
        tokenClip.addRoundRect(tokenRect, corner, corner, Path.Direction.CW);
        canvas.clipPath(tokenClip);
        if (token.art != null) {
            centerCropSquare(token.art, tokenSrcRect);
            canvas.drawBitmap(token.art, tokenSrcRect, tokenRect, bitmapPaint);
            canvas.drawRect(tokenRect, tokenScrim);
        } else {
            canvas.drawRect(tokenRect, tokenSurface);
        }

        tokenLifeText.setTextSize(width * 0.4f);
        canvas.drawText(String.valueOf(token.life), cx,
                cy + tokenLifeText.getTextSize() * 0.35f, tokenLifeText);

        tokenSmallText.setTextSize(Math.max(dp(9), width * 0.13f));
        canvas.drawText(ellipsize(token.name, tokenSmallText, width - dp(8)), cx,
                tokenRect.bottom - dp(4), tokenSmallText);
        if (token.tax > 0) {
            canvas.drawText("Tax +" + token.tax, cx,
                    tokenRect.top + tokenSmallText.getTextSize() + dp(3), tokenSmallText);
        }
        canvas.restore();

        canvas.drawRoundRect(tokenRect, corner, corner,
                token.playerId == focusedTokenId ? focusedBorder : idleBorder);
    }

    /** The largest centered square of a bitmap, for art of any aspect ratio. */
    private static void centerCropSquare(Bitmap bitmap, Rect out) {
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int left = (bitmap.getWidth() - size) / 2;
        int top = (bitmap.getHeight() - size) / 2;
        out.set(left, top, left + size, top + size);
    }

    private static String ellipsize(String text, Paint paint, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int length = text.length();
        while (length > 1 && paint.measureText(text, 0, length) + paint.measureText(ellipsis)
                > maxWidth) {
            length--;
        }
        return text.substring(0, length) + ellipsis;
    }

    /** A Flying card's footprint on the table, tethered to the card, so its altitude reads. */
    private void drawGroundShadow(Canvas canvas, CardPose pose) {
        float[] corners = pose.shadowCorners;
        shadowPath.rewind();
        shadowPath.moveTo(corners[0], corners[1]);
        for (int i = 1; i < 4; i++) {
            shadowPath.lineTo(corners[i * 2], corners[i * 2 + 1]);
        }
        shadowPath.close();
        canvas.drawPath(shadowPath, shadowFill);

        altitudePath.rewind();
        altitudePath.moveTo(pose.groundX, pose.groundY);
        altitudePath.lineTo(pose.centerX, pose.centerY);
        canvas.drawPath(altitudePath, altitudePaint);
    }

    /** A dashed line up into flyer altitude: this card can touch what floats there. */
    private void drawReachIndicator(Canvas canvas, CardPose pose) {
        altitudePath.rewind();
        altitudePath.moveTo(pose.centerX, pose.centerY);
        altitudePath.lineTo(pose.skyX, pose.skyY);
        canvas.drawPath(altitudePath, altitudePaint);
        canvas.drawCircle(pose.skyX, pose.skyY, dp(4), altitudePaint);
    }

    private void drawFloating(Canvas canvas, CardPose pose, RenderState state) {
        // Slightly larger than the physical card at scale 1, so the copy reads as "the details
        // view" rather than a duplicate lying about its size.
        float width = pose.baseWidthPx * state.scale * 1.4f;
        float height = width * state.bitmap.getHeight() / state.bitmap.getWidth();
        float left = pose.centerX - width / 2f;
        // Hover above the physical card so both stay visible.
        float bottom = pose.centerY - pose.baseWidthPx * 0.35f;
        RectF dst = new RectF(left, bottom - height, left + width, bottom);

        canvas.drawBitmap(state.bitmap, null, dst, bitmapPaint);
        canvas.drawRoundRect(dst, dp(6), dp(6),
                pose.key.equals(focusedKey) ? focusedBorder : idleBorder);
        state.lastRect.set(dst);
        drawChips(canvas, state, dst.left, dst.top - dp(10));
    }

    private void drawPlaced(Canvas canvas, CardPose pose, RenderState state) {
        float[] src = {
                0, 0,
                state.bitmap.getWidth(), 0,
                state.bitmap.getWidth(), state.bitmap.getHeight(),
                0, state.bitmap.getHeight(),
        };
        placeMatrix.setPolyToPoly(src, 0, pose.corners, 0, 4);
        canvas.drawBitmap(state.bitmap, placeMatrix, bitmapPaint);

        state.lastRect.set(pose.corners[0], pose.corners[1], pose.corners[0], pose.corners[1]);
        for (int i = 1; i < 4; i++) {
            state.lastRect.union(pose.corners[i * 2], pose.corners[i * 2 + 1]);
        }
        if (pose.key.equals(focusedKey)) {
            canvas.drawRect(state.lastRect, focusedBorder);
        }
        drawChips(canvas, state, pose.corners[0], pose.corners[1] - dp(10));
    }

    /** A row of counter chips, anchored just above the card's top-left. */
    private void drawChips(Canvas canvas, RenderState state, float startX, float baselineY) {
        float x = startX;
        float padding = dp(8);
        float height = dp(22);
        float gap = dp(6);

        List<String> labels = new ArrayList<>(state.chips);
        if (!state.summary.isEmpty()) {
            labels.add(0, state.summary);
        }

        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            float textWidth = chipText.measureText(label);
            RectF chip = new RectF(x, baselineY - height, x + textWidth + padding * 2, baselineY);
            boolean highlight = i == 0 && !state.summary.isEmpty();
            canvas.drawRoundRect(chip, height / 2, height / 2,
                    highlight ? chipHighlight : chipBackground);
            canvas.drawText(label, x + padding, baselineY - dp(7), chipText);
            x = chip.right + gap;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
