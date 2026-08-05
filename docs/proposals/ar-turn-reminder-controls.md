# Turn and reminder controls inside AR

**Status: proposed** — not attempted. Depends only on shipped features (turn tracking,
reminders, the AR bridge), so it is independently buildable now.

## Motivation

Turn state and reminders currently cross the AR bridge display-only: the AR view shows the ▶
turn marker and reminder chips but cannot change anything. During a game played mostly through
the AR view, ending a turn or dismissing a fired reminder means leaving AR for the game
screen.

## Design

- **End turn from AR:** a button on the AR panel (or a tap on the active player's life token
  badge) advances the turn in the session copy; ▶ and due-reminder chips update live.
- **Dismiss reminders from AR:** tapping a due reminder chip on a badge marks it done.
- **Bridge widening — the deliberate part:** `applyArPlayers` currently merges back only life
  and casts by design, so a stale AR result can never corrupt the game. Widening it means the
  result payload also carries `activePlayerId`, `turn`, and the surviving reminder ids, and
  the web side merges them with the same defensive posture (ignore unknown ids, clamp turn to
  ≥ current). Reminder *creation* stays web-only — typing text in AR is not worth the UI.

## Scope guard

This changes the bridge contract in both directions. It should be built as its own piece of
work with the merge rules written first, not slipped into another feature.
