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
 * printed keywords draw muted and are not selectable.
 *
 * <p>Stat counters live in the same gesture. Rails above (power) and below (toughness) the wheel
 * are rate joysticks: holding a finger right of the wheel's centre-line ticks the pending value
 * up, left ticks it down, faster the farther out, and lifting commits the composed kind as one
 * counter. Quick spots beside the wheel add the common kinds — -1/-1 on the left, +1/+1 on the
 * right — one per tap, repeating while held.
 *
 * <p>The wheel is a multi-edit menu: every action applies immediately and the wheel stays up, so
 * one visit can grant a counter and a keyword. Tapping the hub ✕ or dead space dismisses it.
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

        /** A stat delta from a rail commit or a quick-spot tick, e.g. (+2, 0) or (-1, -1). */
        void onStatApplied(int power, int toughness);

        /** The wheel is leaving the screen — fired only for dismissals now, not selections. */
        void onDismissed();
    }

    private static final int MAX_PER_RING = 8;
    private static final float GAP_DEGREES = 2f;
    private static final float HUB_RADIUS_DP = 32f;
    private static final float INNER_RADIUS_DP = 92f;
    private static final float OUTER_RADIUS_DP = 150f;
    private static final float RAIL_GAP_DP = 12f;
    private static final float RAIL_HEIGHT_DP = 44f;
    private static final float SPOT_GAP_DP = 10f;
    private static final float SPOT_RADIUS_DP = 26f;
    /** No ticking this close to the centre-line, so hovering there parks the dial. */
    private static final float RAIL_DEAD_ZONE_DP = 20f;
    private static final float ZONE_SLOP_DP = 8f;
    private static final long RAIL_TICK_SLOW_MS = 700;
    private static final long RAIL_TICK_FAST_MS = 150;
    private static final long SPOT_REPEAT_DELAY_MS = 500;
    private static final long SPOT_REPEAT_START_MS = 550;
    private static final long SPOT_REPEAT_STEP_MS = 50;
    private static final long SPOT_REPEAT_FLOOR_MS = 160;

    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hoverGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final RectF powerRail = new RectF();
    private final RectF toughnessRail = new RectF();

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

    private float leftSpotX;
    private float rightSpotX;
    private float spotRadius;
    /** True from the finger entering a rail until lift (commit) or sliding back into the wheel
     *  (cancel). While dialing, spots are inert and keyword segments dim and stop hovering. */
    private boolean dialActive;
    private int pendingPower;
    private int pendingToughness;
    private boolean railTickScheduled;
    /** -1 while the -1/-1 spot is held, +1 for +1/+1, 0 otherwise. */
    private int heldSpot;
    private int spotTicks;
    private float lastTouchX;
    private float lastTouchY;
    private final Runnable railTicker = this::railTick;
    private final Runnable spotTicker = this::spotTick;

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
        activeBorder.setStyle(Paint.Style.STROKE);
        activeBorder.setStrokeWidth(dp(2));
        activeBorder.setColor(Color.argb(235, 230, 126, 34));
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

        // Shrink the whole wheel on screens too small for the full 300dp diameter plus the
        // stat controls: quick spots need width beside the wheel, rails need height beyond it.
        float sideExtra = dp(SPOT_GAP_DP) + 2 * dp(SPOT_RADIUS_DP) + dp(6);
        float vertExtra = dp(RAIL_GAP_DP) + dp(RAIL_HEIGHT_DP) + dp(6);
        float scale = Math.min(1f, Math.min(
                (getWidth() / 2f - sideExtra) / dp(OUTER_RADIUS_DP),
                (getHeight() / 2f - vertExtra) / dp(OUTER_RADIUS_DP)));
        hubRadius = dp(HUB_RADIUS_DP) * scale;
        innerRadius = dp(INNER_RADIUS_DP) * scale;
        outerRadius = dp(OUTER_RADIUS_DP) * scale;
        if (entries.size() <= MAX_PER_RING) {
            // One ring only: let it span the full band instead of leaving an empty outer ring.
            innerRadius = outerRadius;
        }
        centerX = clamp(cx, outerRadius + sideExtra, getWidth() - outerRadius - sideExtra);
        centerY = clamp(cy, outerRadius + vertExtra, getHeight() - outerRadius - vertExtra);
        layoutStatControls();
        layoutLabels();

        hoverIndex = -1;
        resetStatGesture();
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

    /**
     * Swaps in freshly built entries after an action, keeping the wheel's geometry: the menu is
     * persistent, so a just-toggled keyword must re-highlight without a close-and-reopen.
     */
    void refreshEntries(List<Entry> newEntries) {
        if (!isShowing() || newEntries.isEmpty()) {
            return;
        }
        entries.clear();
        entries.addAll(newEntries);
        innerCount = Math.min(entries.size(), MAX_PER_RING);
        layoutLabels();
        invalidate();
    }

    void hide() {
        if (!isShowing()) {
            return;
        }
        resetStatGesture();
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
        lastTouchX = event.getX();
        lastTouchY = event.getY();
        if (openingGesture && !openingMoved) {
            if (Float.isNaN(openingStartX)) {
                openingStartX = event.getX();
                openingStartY = event.getY();
            } else if (Math.hypot(event.getX() - openingStartX, event.getY() - openingStartY)
                    > touchSlop) {
                openingMoved = true;
            }
        }
        boolean armed = !openingGesture || openingMoved;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (armed) {
                    trackFinger(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_UP: {
                boolean opening = openingGesture;
                openingGesture = false;
                boolean dialed = dialActive;
                int spot = heldSpot;
                int index = armed ? segmentAt(event.getX(), event.getY()) : -1;
                setHover(-1);
                if (dialed) {
                    commitPending();
                } else if (index >= 0) {
                    select(entries.get(index));
                } else if (!opening && spot == 0
                        && railAt(event.getX(), event.getY()) == 0
                        && spotAt(event.getX(), event.getY()) == 0) {
                    // A tap on the hub or dead space closes; the opening press lifting in place
                    // instead parks the wheel as a persistent multi-edit menu.
                    hide();
                }
                resetStatGesture();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                openingGesture = false;
                setHover(-1);
                resetStatGesture();
                return true;
            default:
                return true;
        }
    }

    /** Routes a moving finger to the dial rails, the quick spots, or keyword segment hover. */
    private void trackFinger(float x, float y) {
        if (dialActive) {
            if (Math.hypot(x - centerX, y - centerY) <= outerRadius) {
                // Sliding back into the wheel abandons the pending counter and re-arms keywords.
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                resetStatGesture();
            } else {
                // Still dialing: spots are inert, so the finger can travel around the wheel
                // between rails without side effects. Ticking runs only inside a rail.
                if (railAt(x, y) != 0 && !railTickScheduled && railInterval(x) > 0) {
                    railTickScheduled = true;
                    postDelayed(railTicker, railInterval(x));
                }
                invalidate();
                return;
            }
        }
        int rail = railAt(x, y);
        if (rail != 0) {
            stopSpotHold();
            setHover(-1);
            dialActive = true;
            if (railInterval(x) > 0) {
                railTickScheduled = true;
                postDelayed(railTicker, railInterval(x));
            }
            invalidate();
            return;
        }
        int spot = spotAt(x, y);
        if (spot != heldSpot) {
            stopSpotHold();
            if (spot != 0) {
                startSpotHold(spot);
            }
        }
        if (spot != 0) {
            setHover(-1);
            return;
        }
        setHover(segmentAt(x, y));
    }

    /** One rail tick: nudge the pending stat by the finger's side of the centre-line. */
    private void railTick() {
        railTickScheduled = false;
        int rail = dialActive ? railAt(lastTouchX, lastTouchY) : 0;
        long interval = railInterval(lastTouchX);
        if (rail == 0 || interval <= 0) {
            return; // Left the rail or parked on the centre-line; movement reschedules.
        }
        int sign = lastTouchX > centerX ? 1 : -1;
        if (rail == 1) {
            pendingPower += sign;
        } else {
            pendingToughness += sign;
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
        railTickScheduled = true;
        postDelayed(railTicker, interval);
    }

    /** Tick period for a finger at x: slow near the centre-line, fast far out; 0 in the dead
     *  zone, so the dial parks instead of creeping. */
    private long railInterval(float x) {
        float offset = Math.abs(x - centerX) - dp(RAIL_DEAD_ZONE_DP);
        if (offset <= 0) {
            return 0;
        }
        float reach = Math.max(dp(1), powerRail.width() / 2f - dp(RAIL_DEAD_ZONE_DP));
        float speed = Math.min(1f, offset / reach);
        return (long) (RAIL_TICK_SLOW_MS - speed * (RAIL_TICK_SLOW_MS - RAIL_TICK_FAST_MS));
    }

    private void commitPending() {
        if (pendingPower == 0 && pendingToughness == 0) {
            return; // Dialed back to nothing: a clean cancel.
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (listener != null) {
            listener.onStatApplied(pendingPower, pendingToughness);
        }
    }

    /** Entering a spot applies one counter at once — a tap is one tick — then repeats while
     *  held, starting slow and accelerating. */
    private void startSpotHold(int spot) {
        heldSpot = spot;
        spotTicks = 0;
        applySpotTick();
        postDelayed(spotTicker, SPOT_REPEAT_DELAY_MS);
    }

    private void spotTick() {
        if (heldSpot == 0) {
            return;
        }
        applySpotTick();
        spotTicks++;
        postDelayed(spotTicker, Math.max(SPOT_REPEAT_FLOOR_MS,
                SPOT_REPEAT_START_MS - spotTicks * SPOT_REPEAT_STEP_MS));
    }

    private void applySpotTick() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) {
            listener.onStatApplied(heldSpot, heldSpot);
        }
        invalidate();
    }

    private void stopSpotHold() {
        removeCallbacks(spotTicker);
        if (heldSpot != 0) {
            heldSpot = 0;
            invalidate();
        }
    }

    /** Clears all per-touch stat state: pending dial, scheduled ticks, and a held spot. */
    private void resetStatGesture() {
        removeCallbacks(railTicker);
        railTickScheduled = false;
        dialActive = false;
        pendingPower = 0;
        pendingToughness = 0;
        stopSpotHold();
        invalidate();
    }

    /** 1 for the power rail, 2 for toughness, 0 elsewhere, with a little touch slop. */
    private int railAt(float x, float y) {
        float slop = dp(ZONE_SLOP_DP);
        if (x >= powerRail.left - slop && x <= powerRail.right + slop
                && y >= powerRail.top - slop && y <= powerRail.bottom + slop) {
            return 1;
        }
        if (x >= toughnessRail.left - slop && x <= toughnessRail.right + slop
                && y >= toughnessRail.top - slop && y <= toughnessRail.bottom + slop) {
            return 2;
        }
        return 0;
    }

    /** -1 for the -1/-1 spot, +1 for +1/+1, 0 elsewhere. */
    private int spotAt(float x, float y) {
        float reach = spotRadius + dp(ZONE_SLOP_DP);
        if (Math.hypot(x - leftSpotX, y - centerY) <= reach) {
            return -1;
        }
        if (Math.hypot(x - rightSpotX, y - centerY) <= reach) {
            return 1;
        }
        return 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(railTicker);
        removeCallbacks(spotTicker);
        super.onDetachedFromWindow();
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
        if (entry.custom) {
            // The free-text dialog needs the screen, so only Custom… still closes the wheel.
            Listener chosen = listener;
            hide();
            if (chosen != null) {
                chosen.onEntrySelected(entry);
            }
            return;
        }
        // Persistent menu: the action applies now and the wheel stays up for more edits;
        // the listener re-supplies entries via refreshEntries() so the toggle re-highlights.
        if (listener != null) {
            listener.onEntrySelected(entry);
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
        drawStatControls(canvas);

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
        int color = segmentColor(entries.get(index));
        if (dialActive) {
            // The dial owns the gesture: recede the keyword ring to a faint outline.
            color = Color.argb(Color.alpha(color) / 3,
                    Color.red(color), Color.green(color), Color.blue(color));
        }
        segmentPaint.setColor(color);
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
        int alpha = entries.get(index).locked ? 170 : 255;
        labelPaint.setAlpha(dialActive ? alpha / 3 : alpha);
        float lineHeight = textSize * 1.15f;
        float firstY = y - lineHeight * (lines.length - 1) / 2f;
        for (int k = 0; k < lines.length; k++) {
            canvas.drawText(lines[k], x, firstY + k * lineHeight + textSize * 0.35f, labelPaint);
        }
    }

    /** The hub names the hovered keyword in full — labels out on the rings can be tight. */
    private void drawHubText(Canvas canvas) {
        if (dialActive) {
            // Live readout of the counter being composed on the rails.
            hubTitlePaint.setTextSize(dp(15));
            canvas.drawText(CardCounters.statLabel(pendingPower, pendingToughness),
                    centerX, centerY - dp(2), hubTitlePaint);
            String hint = pendingPower == 0 && pendingToughness == 0
                    ? "Slide left / right" : "Release to apply";
            canvas.drawText(hint, centerX, centerY + dp(12), hubHintPaint);
            return;
        }
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

    /** Positions the power/toughness rails and the ±1/±1 quick spots around the wheel. */
    private void layoutStatControls() {
        float railHalfWidth = Math.min(outerRadius + dp(SPOT_GAP_DP), getWidth() / 2f - dp(8));
        float gap = dp(RAIL_GAP_DP);
        float height = dp(RAIL_HEIGHT_DP);
        powerRail.set(centerX - railHalfWidth, centerY - outerRadius - gap - height,
                centerX + railHalfWidth, centerY - outerRadius - gap);
        toughnessRail.set(centerX - railHalfWidth, centerY + outerRadius + gap,
                centerX + railHalfWidth, centerY + outerRadius + gap + height);
        spotRadius = dp(SPOT_RADIUS_DP);
        leftSpotX = centerX - outerRadius - dp(SPOT_GAP_DP) - spotRadius;
        rightSpotX = centerX + outerRadius + dp(SPOT_GAP_DP) + spotRadius;
    }

    private void drawStatControls(Canvas canvas) {
        int engagedRail = dialActive ? railAt(lastTouchX, lastTouchY) : 0;
        drawRail(canvas, powerRail, "POWER", engagedRail == 1);
        drawRail(canvas, toughnessRail, "TOUGHNESS", engagedRail == 2);
        drawSpot(canvas, leftSpotX, "-1/-1", heldSpot == -1);
        drawSpot(canvas, rightSpotX, "+1/+1", heldSpot == 1);
    }

    /** A rail: rounded bar with −/+ ends, a centre-line notch, and a finger marker while
     *  engaged — right of the notch ticks up, left ticks down, farther is faster. */
    private void drawRail(Canvas canvas, RectF rail, String name, boolean engaged) {
        float corner = rail.height() / 2f;
        canvas.drawRoundRect(rail, corner, corner, hubFill);
        canvas.drawRoundRect(rail, corner, corner, engaged ? activeBorder : hubBorder);

        float midY = rail.centerY();
        canvas.drawLine(centerX, midY - dp(6), centerX, midY + dp(6),
                engaged ? activeBorder : hubBorder);

        labelPaint.setTextSize(dp(11));
        labelPaint.setAlpha(engaged || !dialActive ? 255 : 130);
        canvas.drawText(name, centerX, midY + dp(4), labelPaint);
        labelPaint.setTextSize(dp(14));
        canvas.drawText("−", rail.left + dp(16), midY + dp(5), labelPaint);
        canvas.drawText("+", rail.right - dp(16), midY + dp(5), labelPaint);

        if (engaged) {
            float markerX = clamp(lastTouchX, rail.left + dp(8), rail.right - dp(8));
            canvas.drawLine(markerX, rail.top + dp(4), markerX, rail.bottom - dp(4),
                    activeBorder);
        }
    }

    private void drawSpot(Canvas canvas, float spotX, String label, boolean held) {
        canvas.drawCircle(spotX, centerY, spotRadius, hubFill);
        canvas.drawCircle(spotX, centerY, spotRadius, held ? activeBorder : hubBorder);
        labelPaint.setTextSize(dp(11));
        // Spots are inert while a rail dial is in flight; fade them so that reads at a glance.
        labelPaint.setAlpha(dialActive ? 85 : 255);
        canvas.drawText(label, spotX, centerY + dp(4), labelPaint);
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
