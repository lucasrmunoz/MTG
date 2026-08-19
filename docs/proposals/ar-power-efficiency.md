# AR power and thermal efficiency

**Status: implemented** (2026-08-19, awaiting commit) — three measures that shed sustained
per-frame work on top of ARCore's unavoidable camera + SLAM + GL baseline.

## What it is

The AR screen ran everything hot for the whole session: full-frame OCR every 700 ms, plane
finding continuously, and whatever camera config ARCore preferred (60 fps and depth-sensor
configs on devices that offer them). Each now runs only when it earns its cost.

## Design

- **Ambient OCR idle backoff** (`CardIdentifier`) — after 12 s with no adoption and no
  deliberate scan action, ambient OCR drops from 700 ms to a 3 s cadence. Any adoption, the
  Rescan button, the duplicate-scan tap, a dismiss, or a guide toggle snaps it back to hot.
  Guide mode is untouched (350 ms while aimed — that's a deliberate gesture).
- **Plane finding gate** (`ArCardActivity.updatePlaneFinding`, GL thread) — horizontal plane
  finding turns off once no placement is plausible: no pending placement armed, no unlocated
  card, and 20 s past the last placement-intent signal. Intent signals re-arm it: session
  start, opening the card list, arming a placement, a token drag starting
  (`CardOverlayView.Listener.onTokenDragStarted`), rescan, guide toggle. Reconfigures run on
  the database executor, serialised with database rebuilds. Hit tests already accept feature
  `Point`s alongside planes, so a placement during a re-finding beat degrades instead of
  failing. *Inferred, verify at the table:* previously found planes appear to stay
  hit-testable while finding is off; if drops ever miss on a long-idle session, the fallback
  and the 20 s grace window are the knobs.
- **Cool camera config** (`applyCoolCameraConfig`) — at session creation (before any
  trackables, so the `setCameraConfig` caveat doesn't apply), a `CameraConfigFilter` pins
  capture to 30 fps and refuses depth-sensor configs. ARCore documents that it otherwise
  *prioritises* 60 fps and depth-sensor configs on capable devices — both pure cost for a
  card table.

Related: scanning also pauses entirely while the keyword wheel is up, and the Motion pill
stills the Flying bob (see `counter-dial.md` and `flying-reach-altitude.md`).

## Future extensions

- Overlay redraw skipping when poses are static — minor; handheld jitter makes frames dirty
  almost always.
- Tick OCR fully off (not just slow) in game mode once all commanders are bound.
