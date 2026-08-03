package com.lucasmunoz.mtg.ar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

        CardPose(String key, float[] corners, float centerX, float centerY, float baseWidthPx) {
            this.key = key;
            this.corners = corners;
            this.centerX = centerX;
            this.centerY = centerY;
            this.baseWidthPx = baseWidthPx;
        }
    }

    interface Listener {
        /** A tap focused a different card; the counter panel should follow. */
        void onFocusChanged(String key);

        /** A tap hit no card: a chance to place a pending card at this screen point. */
        void onTapEmpty(float x, float y);
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
    private volatile String focusedKey;
    private Listener listener;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint chipBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusedBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix placeMatrix = new Matrix();

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

    /** Called from the GL thread once per rendered frame. */
    void postGeometry(List<CardPose> newPoses) {
        poses = newPoses;
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
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        return true;
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

    @Override
    protected void onDraw(Canvas canvas) {
        for (CardPose pose : poses) {
            RenderState state = renders.get(pose.key);
            if (state == null || state.bitmap == null) {
                continue;
            }
            if (state.placed) {
                drawPlaced(canvas, pose, state);
            } else {
                drawFloating(canvas, pose, state);
            }
        }
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
