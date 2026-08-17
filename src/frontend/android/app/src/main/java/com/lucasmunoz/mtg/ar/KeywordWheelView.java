package com.lucasmunoz.mtg.ar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;

/**
 * A radial keyword picker summoned by long-pressing a card: the presets fan out around the press
 * point in one or two rings, so granting a keyword counter is a flick instead of a dialog trip.
 * Segments the card already has as counters draw highlighted and remove on selection; the card's
 * printed keywords draw muted and are not selectable. Opened mid-gesture, the finger can drag
 * straight onto a segment and release to choose; lifting without choosing keeps the wheel up for
 * a plain tap, and tapping the hub or outside dismisses it.
 */
public final class KeywordWheelView extends View {

    /** One wheel segment: a preset keyword, or the "Custom…" tail. */
    static final class Entry {
        final String label;
        /** Already on the card as a counter: highlighted, and selecting removes it. */
        final boolean active;
        /** Printed on the card itself: shown for completeness, never selectable. */
        final boolean locked;
        /** The "Custom…" tail: selecting opens the free-text dialog instead. */
        final boolean custom;

        Entry(String label, boolean active, boolean locked, boolean custom) {
            this.label = label;
            this.active = active;
            this.locked = locked;
            this.custom = custom;
        }
    }

    interface Listener {
        void onEntrySelected(Entry entry);

        /** The wheel is leaving the screen — fired for selections and dismissals alike. */
        void onDismissed();
    }

    private static final int MAX_PER_RING = 8;
    private static final float GAP_DEGREES = 2f;
    private static final float HUB_RADIUS_DP = 32f;
    private static final float INNER_RADIUS_DP = 92f;
    private static final float OUTER_RADIUS_DP = 150f;

    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hoverGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private Listener listener;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String[]> entryLines = new ArrayList<>();
    private final List<Float> entryTextSizes = new ArrayList<>();
    private int innerCount;
    private float centerX;
    private float centerY;
    private float hubRadius;
    private float innerRadius;
    private float outerRadius;
    private int hoverIndex = -1;
    /** True while the long-press that opened the wheel is still down: its lift must not dismiss. */
    private boolean openingGesture;
    /** The wheel centre can be clamped away from the finger near screen edges, so the opening
     *  gesture only starts hovering segments once it has genuinely moved past touch slop. */
    private final int touchSlop;
    private float openingStartX = Float.NaN;
    private float openingStartY = Float.NaN;
    private boolean openingMoved;
    private boolean hiding;

    public KeywordWheelView(Context context) {
        this(context, null);
    }

    /** Inflated from activity_ar_card.xml, which uses this constructor. */
    public KeywordWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        segmentPaint.setStyle(Paint.Style.STROKE);
        hoverGlow.setStyle(Paint.Style.STROKE);
        hoverGlow.setColor(Color.argb(60, 255, 255, 255));
        hubFill.setColor(Color.argb(235, 26, 26, 46));
        hubBorder.setStyle(Paint.Style.STROKE);
        hubBorder.setStrokeWidth(dp(1.5f));
        hubBorder.setColor(Color.argb(160, 155, 89, 182));
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        hubTitlePaint.setColor(Color.WHITE);
        hubTitlePaint.setTextAlign(Paint.Align.CENTER);
        hubTitlePaint.setFakeBoldText(true);
        hubTitlePaint.setTextSize(dp(13));
        hubTitlePaint.setShadowLayer(dp(3), 0, dp(1), Color.argb(220, 0, 0, 0));
        hubHintPaint.setColor(Color.argb(220, 189, 195, 199));
        hubHintPaint.setTextAlign(Paint.Align.CENTER);
        hubHintPaint.setTextSize(dp(10));
        hubHintPaint.setShadowLayer(dp(3), 0, dp(1), Color.argb(220, 0, 0, 0));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE && !hiding;
    }

    /**
     * Opens the wheel around (cx, cy), clamped so it fits on screen. With {@code midGesture} the
     * opening long-press is still down and its motion events arrive here via the overlay's relay.
     */
    void show(float cx, float cy, List<Entry> newEntries, boolean midGesture) {
        if (newEntries.isEmpty() || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        entries.clear();
        entries.addAll(newEntries);
        innerCount = Math.min(entries.size(), MAX_PER_RING);

        // Shrink the whole wheel on screens too small for the full 300dp diameter.
        float scale = Math.min(1f,
                0.45f * Math.min(getWidth(), getHeight()) / dp(OUTER_RADIUS_DP));
        hubRadius = dp(HUB_RADIUS_DP) * scale;
        innerRadius = dp(INNER_RADIUS_DP) * scale;
        outerRadius = dp(OUTER_RADIUS_DP) * scale;
        if (entries.size() <= MAX_PER_RING) {
            // One ring only: let it span the full band instead of leaving an empty outer ring.
            innerRadius = outerRadius;
        }
        float margin = outerRadius + dp(6);
        centerX = clamp(cx, margin, getWidth() - margin);
        centerY = clamp(cy, margin, getHeight() - margin);
        layoutLabels();

        hoverIndex = -1;
        openingGesture = midGesture;
        openingStartX = Float.NaN;
        openingStartY = Float.NaN;
        openingMoved = false;
        hiding = false;
        setVisibility(VISIBLE);
        setPivotX(centerX);
        setPivotY(centerY);
        setScaleX(0.85f);
        setScaleY(0.85f);
        setAlpha(0f);
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).setListener(null);
        invalidate();
    }

    void hide() {
        if (!isShowing()) {
            return;
        }
        hiding = true;
        if (listener != null) {
            listener.onDismissed();
        }
        animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(120)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Invisible, not gone: the view must keep its size for the next show().
                        setVisibility(INVISIBLE);
                        setScaleX(1f);
                        setScaleY(1f);
                        setAlpha(1f);
                        animate().setListener(null);
                    }
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isShowing()) {
            return false;
        }
        if (openingGesture && !openingMoved) {
            if (Float.isNaN(openingStartX)) {
                openingStartX = event.getX();
                openingStartY = event.getY();
            } else if (Math.hypot(event.getX() - openingStartX, event.getY() - openingStartY)
                    > touchSlop) {
                openingMoved = true;
            }
        }
        int index = openingGesture && !openingMoved
                ? -1 : segmentAt(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                setHover(index);
                return true;
            case MotionEvent.ACTION_UP: {
                boolean opening = openingGesture;
                openingGesture = false;
                setHover(-1);
                if (index >= 0) {
                    select(entries.get(index));
                } else if (!opening) {
                    // A tap on the hub or outside closes; the opening press lifting in place
                    // instead parks the wheel in tap mode.
                    hide();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                openingGesture = false;
                setHover(-1);
                return true;
            default:
                return true;
        }
    }

    private void setHover(int index) {
        if (index == hoverIndex) {
            return;
        }
        hoverIndex = index;
        if (index >= 0) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
    }

    private void select(Entry entry) {
        if (entry.locked) {
            return; // Printed keywords are part of the card; the wheel stays up.
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Listener chosen = listener;
        hide();
        if (chosen != null) {
            chosen.onEntrySelected(entry);
        }
    }

    /** The segment index under a point, or -1 for the hub, the gaps beyond, or no outer ring. */
    private int segmentAt(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance < hubRadius || distance > outerRadius) {
            return -1;
        }
        boolean inner = distance < innerRadius;
        int count = inner ? innerCount : entries.size() - innerCount;
        if (count <= 0) {
            return -1;
        }
        // drawArc angles: 0° at three o'clock; segments start at twelve, so shift by 90.
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx)) + 90f;
        angle = (angle % 360f + 360f) % 360f;
        int slot = Math.min((int) (angle / (360f / count)), count - 1);
        return inner ? slot : innerCount + slot;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (entries.isEmpty()) {
            return;
        }
        canvas.drawColor(Color.argb(96, 0, 0, 0));
        for (int i = 0; i < entries.size(); i++) {
            if (i != hoverIndex) {
                drawSegment(canvas, i, false);
            }
        }
        if (hoverIndex >= 0) {
            drawSegment(canvas, hoverIndex, true);
        }

        canvas.drawCircle(centerX, centerY, hubRadius, hubFill);
        canvas.drawCircle(centerX, centerY, hubRadius, hubBorder);
        drawHubText(canvas);

        for (int i = 0; i < entries.size(); i++) {
            drawLabel(canvas, i);
        }
    }

    /** A ring segment as a thick arc stroke; hovering widens the band and adds a glow edge. */
    private void drawSegment(Canvas canvas, int index, boolean hovered) {
        boolean inner = index < innerCount;
        int count = inner ? innerCount : entries.size() - innerCount;
        int slot = inner ? index : index - innerCount;
        float sweep = 360f / count;
        float start = -90f + slot * sweep + GAP_DEGREES / 2f;

        float bandIn = inner ? hubRadius + dp(3) : innerRadius + dp(1.5f);
        float bandOut = (inner && entries.size() > innerCount ? innerRadius - dp(1.5f)
                : outerRadius) + (hovered ? dp(5) : 0);
        float mid = (bandIn + bandOut) / 2f;
        arcRect.set(centerX - mid, centerY - mid, centerX + mid, centerY + mid);

        segmentPaint.setStrokeWidth(bandOut - bandIn);
        segmentPaint.setColor(segmentColor(entries.get(index)));
        canvas.drawArc(arcRect, start, sweep - GAP_DEGREES, false, segmentPaint);
        if (hovered) {
            hoverGlow.setStrokeWidth(bandOut - bandIn);
            canvas.drawArc(arcRect, start, sweep - GAP_DEGREES, false, hoverGlow);
        }
    }

    private static int segmentColor(Entry entry) {
        if (entry.active) {
            return Color.argb(235, 230, 126, 34);
        }
        if (entry.locked) {
            return Color.argb(140, 155, 89, 182);
        }
        return Color.argb(235, 26, 26, 46);
    }

    private void drawLabel(Canvas canvas, int index) {
        boolean inner = index < innerCount;
        int count = inner ? innerCount : entries.size() - innerCount;
        int slot = inner ? index : index - innerCount;
        float sweep = 360f / count;
        double midAngle = Math.toRadians(-90f + (slot + 0.5f) * sweep);
        float labelR = inner ? (hubRadius + innerRadius) / 2f : (innerRadius + outerRadius) / 2f;
        float x = centerX + labelR * (float) Math.cos(midAngle);
        float y = centerY + labelR * (float) Math.sin(midAngle);

        String[] lines = entryLines.get(index);
        float textSize = entryTextSizes.get(index);
        labelPaint.setTextSize(textSize);
        labelPaint.setAlpha(entries.get(index).locked ? 170 : 255);
        float lineHeight = textSize * 1.15f;
        float firstY = y - lineHeight * (lines.length - 1) / 2f;
        for (int k = 0; k < lines.length; k++) {
            canvas.drawText(lines[k], x, firstY + k * lineHeight + textSize * 0.35f, labelPaint);
        }
    }

    /** The hub names the hovered keyword in full — labels out on the rings can be tight. */
    private void drawHubText(Canvas canvas) {
        if (hoverIndex < 0) {
            hubTitlePaint.setTextSize(dp(16));
            canvas.drawText("✕", centerX, centerY + dp(16) * 0.35f, hubTitlePaint);
            return;
        }
        Entry entry = entries.get(hoverIndex);
        hubTitlePaint.setTextSize(dp(13));
        canvas.drawText(entry.label, centerX, centerY - dp(2), hubTitlePaint);
        String hint;
        if (entry.custom) {
            hint = "Type your own";
        } else if (entry.locked) {
            hint = "Printed on card";
        } else {
            hint = entry.active ? "Release to remove" : "Release to add";
        }
        canvas.drawText(hint, centerX, centerY + dp(12), hubHintPaint);
    }

    /** Splits each label into at most two lines and shrinks it until it fits its segment. */
    private void layoutLabels() {
        entryLines.clear();
        entryTextSizes.clear();
        for (int i = 0; i < entries.size(); i++) {
            boolean inner = i < innerCount;
            int count = inner ? innerCount : entries.size() - innerCount;
            float labelR = inner ? (hubRadius + innerRadius) / 2f
                    : (innerRadius + outerRadius) / 2f;
            float maxWidth = 2f * labelR * (float) Math.sin(Math.PI / count) - dp(6);

            String[] lines = splitLines(entries.get(i).label);
            float size = dp(12);
            labelPaint.setTextSize(size);
            while (size > dp(8) && widestLine(lines, labelPaint) > maxWidth) {
                size -= dp(0.5f);
                labelPaint.setTextSize(size);
            }
            for (int k = 0; k < lines.length; k++) {
                lines[k] = CardOverlayView.ellipsize(lines[k], labelPaint, maxWidth);
            }
            entryLines.add(lines);
            entryTextSizes.add(size);
        }
    }

    /** Two-word labels break at the space nearest the middle; everything else stays one line. */
    private static String[] splitLines(String label) {
        int best = -1;
        for (int i = label.indexOf(' '); i >= 0; i = label.indexOf(' ', i + 1)) {
            if (best < 0 || Math.abs(i - label.length() / 2) < Math.abs(best - label.length() / 2)) {
                best = i;
            }
        }
        if (best < 0) {
            return new String[] {label};
        }
        return new String[] {label.substring(0, best), label.substring(best + 1)};
    }

    private static float widestLine(String[] lines, Paint paint) {
        float widest = 0f;
        for (String line : lines) {
            widest = Math.max(widest, paint.measureText(line));
        }
        return widest;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
