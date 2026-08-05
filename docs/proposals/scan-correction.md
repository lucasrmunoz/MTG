# Scan correction: remove, re-place, rescan

**Status: shipped** (2026-08-05) — this doc records the design. Standalone: works with any
identification pipeline behind it.

## What it is

Human-error correction for the AR scanner. Misidentified cards used to be permanent: they sat
in the list, bound to the wrong physical card, and re-added themselves when removed. Now every
scanned card's row in the Cards list has:

- **✕ (remove)** — deletes the card from the list, the overlay, the ARCore reference-image
  database (rebuilt, with pose preservation for still-tracked cards; an emptied database is
  installed too, so removing the last card really stops tracking), and the scanner's match
  memory. Without that last step the identifier replays the match and the card rejoins within
  a frame.
- **Re-place** — lifts a located card off its anchor and arms the tap-a-surface placement
  flow, for a card manually placed on the wrong spot. A card the camera is actively tracking
  snaps back on re-lock — remove it instead if the identification itself is wrong.
- **Rescan** (button by the Cards toggle) — a deliberate fresh start: removed names are
  allowed back and rejected readings retried, Mythic-style back-to-back. Removal is therefore
  never permanent, which is why ✕ needs no confirmation dialog.

Commander cards are not removable (they belong to players); they can still be re-placed.

Files: `ArCardActivity.java` (`removeCard`, `rePlaceCard`, `reconfigureDatabaseIfDirty` empty-
database path), `CardIdentifier.java` (`dismiss`, `rescan`, dismissed-names check in
`addMatch`), `CardOverlayView.removeCard`, `ar_card_row.xml`, `activity_ar_card.xml`.
