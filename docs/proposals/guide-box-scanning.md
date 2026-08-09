# Guide-box scanning

**Status: shipped 2026-08-06; identification reworked 2026-08-09** — the deliberate
alternative to ambient scanning, alongside it, not replacing it. The original per-section
read-and-match chain (every candidate line its own Scryfall round trip, serial on one thread)
proved far too slow at the table; identification now matches the whole card's text locally
against a cached Scryfall name catalog (`CardNameCatalog`), and the network is touched once
per adopted card.

## Motivation

Ambient scanning reads titles anywhere in frame (rules text can become a phantom card) and
pairs collector numbers with set codes frame-wide (two cards in frame can mint a printing
neither of them is). The full-card-scanning proposal fixes this by auto-detecting card quads —
a computer-vision problem that failed once for lack of a calibration corpus. Guide-box
scanning gets the same structural confinement by letting the user do the detection: aim one
card into a fixed on-screen outline, the way Mythic Tools scans.

## Design

Pure scan mode **starts with the outline up** — a grey card-aspect (63:88) outline centered
mid-screen; the "✕ Outline" toggle drops back to ambient. While it is up, the scanner reads
**only inside the outline** (plus a 10% margin for cards that overflow it), polling at roughly
double the ambient cadence:

- **Every line in the box** is matched locally against the cached Scryfall name catalog in one
  sweep — the box confines everything to the one aimed card, so the whole card is searched at
  once without frame-wide hazards. Rules-text lines simply match nothing; junk costs no
  network. The collector line is parsed from the same lines.
- **The outline turns green** while card text is being read inside it, Mythic-style; grey
  means aim or lighting isn't giving the OCR anything.
- **Cross-check:** both reads describe the same physical card, so a collector-line hit is
  adopted only when its card name agrees with a name the box matched in the last 5 seconds —
  a misread digit names a different card, and the name is the tiebreak. Local matches are
  instant, so the old race against a slow network title lookup is gone. With no live name
  (foil glare), the collector line stands alone. Disagreements are dropped without being
  remembered as rejected, so a later pass retries once the right name matches.
- **Confirmation:** the first time an aimed card confirms, the status line flashes
  "✓ Name — Set" with a haptic tap: aim, buzz, next card.

Identification stays text-based: OCR → local catalog match → one exact-name fetch for the
card that matched. The catalog (`/catalog/card-names`, ~2 MB) is cached as a single replaced
file like the vendor price cache, refreshed weekly in the background. Matching ranks, never
guesses: an ambiguous read (two names equally close) matches nothing. Scryfall's fuzzy
endpoint survives only as a last resort — a guided pass where the whole box matched nothing,
or a pass before the catalog has ever loaded — one call per pass, not per line. Whole-card
*image* matching the way Mythic Tools does it needs a bundled image-hash index and the shelved
full-card chain — that is the escalation path if text reads stay unreliable, not part of this
feature.

No computer vision, no thresholds, no calibration corpus: the box is fixed view coordinates,
and the view↔image mapping is the same `transformCoordinates2d` affine the ambient scanner
already uses. Confirmed cards join exactly as ambient finds do (Scryfall lookup → reference
images → tracking). Toggling off restores frame-wide ambient scanning unchanged.

## Relationship to full-card scanning

Supersedes phases 1–3 of [full-card scanning](full-card-scanning.md) as the structural fix for
phantom cards and cross-card pairing. The quad-detection chain stays shelved unless ambient
misreads remain painful enough to fund it; the phase-0 capture tooling (corpus recorder +
`tools/scan-harness`) remains in place for any future camera-tuning question.
