# Counter dial around the keyword wheel

**Status: implemented** (2026-08-19, awaiting commit) — flow agreed with Lucas; unit tests
cover the remove-first delta semantics (`CardCountersTest`).

## What it is

The long-press keyword wheel grows counter controls around it, so stat counters are set in
the same hold gesture that grants keywords: rails above/below the wheel dial asymmetric
kinds (+2/+0), quick spots left/right handle the common -1/-1 and +1/+1, and the wheel
becomes a persistent multi-edit menu — one hold can add a +1/+1 counter *and* Haste
(equipment case) before dismissing.

## Design

- **Layout** (all outside the wheel's outer radius, with a visible gap so exiting the wheel
  never clips them accidentally):
  - **Top rail — POWER**, **bottom rail — TOUGHNESS**: horizontal joystick lanes.
  - **Left spot — -1/-1**, **right spot — +1/+1**: quick tap targets for the common kinds.
- **Rails are rate joysticks, not walk-the-distance:** while the finger is in a rail, its
  horizontal offset from the wheel's center-line drives repeated ticks — right of center
  counts up, left counts down, slow near the line (~1–2/sec) and faster farther out, one
  haptic per tick. Sliding back near the center-line stops ticking; sliding back into the
  wheel area cancels the dial and re-lights the keyword segments (nothing is one-way).
- **Compose per touch, commit on lift:** one touch of the rails builds ONE pending counter
  kind, shown live in the hub ("+2/+0 — release to add"); visiting top then bottom in the
  same touch composes mixed kinds (+2/-1). Lifting commits it as a single counter of that
  kind (`CardCounters.addStat`). Walking back to 0/0 before lifting is a clean cancel.
- **Quick spots:** tap = one counter of that kind; press-and-hold = repeat ticks with the
  same slow-then-accelerating rate as the rails.
- **Negative ticks remove first:** a -1/-1 tick on a card holding +1/+1 counters peels one
  off per tick; only past zero does it start adding a `-1/-1` kind. Rails do the same per
  component. The opposite gesture is the undo, so a mis-tap is fixed in the same visit.
- **Scanning pauses while the wheel is up:** `maybeIdentify` is gated on a volatile flag set
  by show/dismiss, so mid-edit frames can't spawn new cards or duplicate-scan toasts under
  the menu. Tracking of already-scanned cards continues.
- **Stat reset:** a Reset button in the panel's stat row (`clearStats`) drops every stat
  counter at once; keywords stay.
- **Persistent multi-edit menu:** selecting a keyword segment no longer dismisses the wheel
  — every action (keyword toggle, spot tap, rail commit) applies immediately, the segment
  re-highlights to show its new state, and the menu stays up. Tapping dead space or the hub
  ✕ ends the session. This replaces select-and-close; the existing "parked tap mode"
  (lift without choosing keeps the wheel up) becomes the norm after every action.

Files this lands in: `android/…/ar/KeywordWheelView.java` (rails, spots, persistent mode),
`ArCardActivity.java` (listener wiring, stat application), `CardCounters.java` (unchanged —
already models arbitrary ±X/±Y kinds with counts).

## Future extensions (belong to this feature; nothing else depends on them)

- **Until-end-of-turn pumps** — "+2/+0 until end of turn" isn't a counter in MTG; if the
  table wants temporary effects tracked, add an until-EOT bucket cleared by the existing
  turn model rather than overloading persistent counters.
- **Rate tuning** — tick intervals and the center dead-zone width will need on-device
  adjustment once it's holdable.
