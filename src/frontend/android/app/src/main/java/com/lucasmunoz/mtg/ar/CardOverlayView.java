package com.lucasmunoz.mtg.ar;

import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the virtual card over the camera view: either floating upright above the physical card
 * (pinch to zoom) or laid perspective-correct on top of it, with its counters as chips.
 *
 * Geometry arrives from the GL thread once per frame as an {@link OverlayState}; this view only
 * ever draws the latest one. Rendering in screen space from the projected pose keeps the whole
 * feature free of a 3D scene graph — the card is a bitmap, placed mode is a polyToPoly warp onto
 * the projected corners of the physical card.
 */
public final class CardOverlayView extends View {

    /** What the AR frame worked out for this draw: where the card is on screen, if anywhere. */
    static final class OverlayState {
        final boolean tracking;
        /** Projected physical-card corners: TL, TR, BR, BL as x,y pairs. */
        final float[] corners;
        final float centerX;
        final float centerY;
        /** Projected width of the physical card in pixels, the basis for floating-card size. */
        final float baseWidthPx;

        OverlayState(boolean tracking, float[] corners, float centerX, float centerY,
                float baseWidthPx) {
            this.tracking = tracking;
            this.corners = corners;
            this.centerX = centerX;
            this.centerY = centerY;
            this.baseWidthPx = baseWidthPx;
        }
    }

    interface Listener {
        /** A tap while the card has a position: toggle floating vs placed. */
        void onToggleMode();

        /** A tap while nothing is tracked: try a manual placement at this screen point. */
        void onManualPlace(float x, float y);
    }

    private volatile OverlayState state;
    private volatile boolean placed;
    private Bitmap cardBitmap;
    private final List<String> chips = new ArrayList<>();
    private String summaryChip = "";
    private float userScale = 1f;
    private Listener listener;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint chipBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        cardBorder.setStyle(Paint.Style.STROKE);
        cardBorder.setStrokeWidth(dp(2));
        cardBorder.setColor(Color.argb(200, 230, 126, 34));

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        userScale = clamp(userScale * detector.getScaleFactor(), 0.35f, 3.5f);
                        invalidate();
                        return true;
                    }
                });

        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                OverlayState current = state;
                if (listener == null) {
                    return false;
                }
                if (current != null && current.tracking) {
                    listener.onToggleMode();
                } else {
                    listener.onManualPlace(event.getX(), event.getY());
                }
                return true;
            }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setCardBitmap(Bitmap bitmap) {
        cardBitmap = bitmap;
        postInvalidate();
    }

    void setPlaced(boolean value) {
        placed = value;
        postInvalidate();
    }

    boolean isPlaced() {
        return placed;
    }

    /** Called from the UI thread whenever counters change. */
    void setChips(List<String> labels, String summary) {
        chips.clear();
        chips.addAll(labels);
        summaryChip = summary;
        invalidate();
    }

    /** Called from the GL thread once per rendered frame. */
    void postState(OverlayState newState) {
        state = newState;
        postInvalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        OverlayState current = state;
        if (current == null || !current.tracking || cardBitmap == null) {
            return;
        }

        if (placed) {
            drawPlaced(canvas, current);
        } else {
            drawFloating(canvas, current);
        }
    }

    private void drawFloating(Canvas canvas, OverlayState current) {
        // Slightly larger than the physical card at scale 1, so the copy reads as "the details
        // view" rather than a duplicate lying about its size.
        float width = current.baseWidthPx * userScale * 1.4f;
        float height = width * cardBitmap.getHeight() / cardBitmap.getWidth();
        float left = current.centerX - width / 2f;
        // Hover above the physical card so both stay visible.
        float bottom = current.centerY - current.baseWidthPx * 0.35f;
        RectF dst = new RectF(left, bottom - height, left + width, bottom);

        canvas.drawBitmap(cardBitmap, null, dst, bitmapPaint);
        canvas.drawRoundRect(dst, dp(6), dp(6), cardBorder);
        drawChips(canvas, dst.left, dst.top - dp(10));
    }

    private void drawPlaced(Canvas canvas, OverlayState current) {
        float[] src = {
                0, 0,
                cardBitmap.getWidth(), 0,
                cardBitmap.getWidth(), cardBitmap.getHeight(),
                0, cardBitmap.getHeight(),
        };
        placeMatrix.setPolyToPoly(src, 0, current.corners, 0, 4);
        canvas.drawBitmap(cardBitmap, placeMatrix, bitmapPaint);
        drawChips(canvas, current.corners[0], current.corners[1] - dp(10));
    }

    /** A row of counter chips, anchored just above the card's top-left. */
    private void drawChips(Canvas canvas, float startX, float baselineY) {
        float x = startX;
        float padding = dp(8);
        float height = dp(22);
        float gap = dp(6);

        List<String> labels = new ArrayList<>(chips);
        if (!summaryChip.isEmpty()) {
            labels.add(0, summaryChip);
        }

        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            float textWidth = chipText.measureText(label);
            RectF chip = new RectF(x, baselineY - height, x + textWidth + padding * 2, baselineY);
            boolean highlight = i == 0 && !summaryChip.isEmpty();
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
