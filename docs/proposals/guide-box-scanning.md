# Guide-box scanning

**Status: shipped 2026-08-06** — the deliberate alternative to ambient scanning, alongside it,
not replacing it.

## Motivation

Ambient scanning reads titles anywhere in frame (rules text can become a phantom card) and
pairs collector numbers with set codes frame-wide (two cards in frame can mint a printing
neither of them is). The full-card-scanning proposal fixes this by auto-detecting card quads —
a computer-vision problem that failed once for lack of a calibration corpus. Guide-box
scanning gets the same structural confinement by letting the user do the detection: aim one
card into a fixed on-screen outline, the way Mythic Tools scans.

## Design

A "⌖ Scan card" toggle in the AR screen shows a grey card-aspect (63:88) outline centered
mid-screen. While it is up, the scanner reads **only inside the outline**, banded by card
layout in view space, where the aimed card is upright:

- **Title** from lines whose centre falls in the top 35% of the box.
- **Collector number and set code** from lines in the bottom 35%, paired with each other there
  — never with anything outside the box.
- The middle 30% — rules text, the fuzzy-match hazard — is ignored entirely.
- **Cross-check:** both reads describe the same physical card, so a collector-line hit is
  adopted only when its card name agrees with a title the box resolved in the last 5 seconds
  — a misread digit names a different card, and the title is the tiebreak. With no live title
  (foil glare), the collector line stands alone. Disagreements are dropped without being
  remembered as rejected, so a later pass retries once the right title resolves.
- **Confirmation:** the first time an aimed card confirms, the status line flashes
  "✓ Name — Set" with a haptic tap: aim, buzz, next card.

No computer vision, no thresholds, no calibration corpus: the box is fixed view coordinates,
and the view↔image mapping is the same `transformCoordinates2d` affine the ambient scanner
already uses. Confirmed cards join exactly as ambient finds do (Scryfall lookup → reference
images → tracking). Toggling off restores frame-wide ambient scanning unchanged.

## Relationship to full-card scanning

Supersedes phases 1–3 of [full-card scanning](full-card-scanning.md) as the structural fix for
phantom cards and cross-card pairing. The quad-detection chain stays shelved unless ambient
misreads remain painful enough to fund it; the phase-0 capture tooling (corpus recorder +
`tools/scan-harness`) remains in place for any future camera-tuning question.
