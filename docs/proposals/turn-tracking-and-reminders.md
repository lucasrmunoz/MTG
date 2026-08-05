# Turn tracking and next-turn trigger reminders

**Status: shipped** (commit `48a6606`, 2026-08-05) — this doc records the design and holds its
future extensions.

## What it is

The Commander game screen tracks whose turn it is and lets any player arm a reminder that
fires on a future turn — "at the start/end of your next turn, do X", including on an
opponent's turn ("Alice's next draw step").

## Design

- **Turn model is deliberately coarse:** `activePlayerId` + a `turn` counter, no phase
  stepper. One **End turn** button on the active player's zone is simultaneously "end of my
  turn" and "start of the next player's turn", which is where nearly all delayed triggers
  anchor. Turn order = seat order (`Player.id`). A zone menu action ("It's their turn")
  corrects the marker without advancing the counter.
- **Reminders** are `{playerId, phase, text, due}` — anchored to a specific seat, labelled
  upkeep / draw / combat / end step. All of a player's reminders come due when their turn
  starts; the outgoing player's end-step reminders come due as they end their turn. Due chips
  turn orange on the zone; tapping one dismisses it (done = gone).
- **Persistence:** storage version 2; v1 saves load with turn fields defaulted.
- **AR:** the bridge carries a ▶ marker and preformatted reminder labels per player,
  display-only; `applyArPlayers` still merges back only life and casts.

Files: `src/frontend/src/lib/game.ts` (model + parsing), `PlayerZone.tsx` (End turn, chips,
reminder editor), `GameBoard.tsx`, `app/game/page.tsx`, `src/frontend/src/lib/ar.ts`,
`android/…/ar/GamePlayer.java`.

## Future extensions (belong to this feature; nothing else depends on them)

- **Recurring reminders** — "at the beginning of each upkeep" enchantments: a `recurring`
  flag that survives dismissal and re-arms every cycle. Build when the table actually wants
  it; the model addition is one boolean plus skip-delete on dismiss.
- **Phase-precise firing** — only if coarse anchors ever prove insufficient at the table;
  would mean a tap-through phase stepper, which is a lot of taps for little gain.
