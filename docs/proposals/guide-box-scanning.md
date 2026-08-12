# Guide-box scanning

**Status: shipped 2026-08-06; identification reworked 2026-08-09; pass consensus added
2026-08-11** — the deliberate
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
- **Consensus:** a guided reading — matched name or collector line — is believed only after a
  second pass reproduces it (`GuideConsensus`), so passes effectively vote before a result is
  shown. This catches the misread the name cross-check cannot: on a basic land a wrong digit
  still names *an* Island, agrees with the box name, and used to be adopted as the wrong
  printing — sticky until a manual rescan. A lone misread rarely repeats, so it loses the
  vote; the true reading repeats every pass and confirms ~350 ms later. Sightings expire with
  the same 5-second TTL as box names, so a re-aim starts a fresh vote.
- **Exact reads silence tolerant ones:** the vote cannot catch junk that repeats every pass —
  an aimed Island's own type line yields "Land", one edit from the playtest card "Lands", and
  the upside-down read's noise landed one edit from "Stand" (a face of "Stand // Deliver"),
  every pass, so both were adopted alongside the card itself. The box holds one card: when
  any line reads a catalog name exactly (`CardNameCatalog.exactMatch`), the pass discards its
  edit-tolerant hits and only exact reads go forward. A pass where glare leaves no line exact
  still gets the tolerant match, so hard reads keep working.
- **Weak evidence must outlast the settling window:** while the user frames the card and
  focus settles, fragments read stably (two identical passes) before the title is readable —
  which adopted "Hand to Hand" (settling blur through the previously unvoted Scryfall fuzzy
  fallback) and "Lands" before "Island" ever read. Edit-tolerant matches and fuzzy titles now
  also require sightings spanning 1.5 s; exact reads and collector lines keep the fast
  two-pass vote. By the time the span elapses, a readable title has appeared and suppresses
  the junk — only a card whose title never reads exactly (foil glare) waits the full span.
- **A recent exact read silences weaker tiers across passes:** the live trace showed title
  fragments ("land") adopting a fuzzy junk card two seconds *after* the title itself had read
  exactly — per-pass suppression cannot see neighbouring passes. For the 5-second guide TTL
  after any exact read, a pass that fails to re-read the title yields nothing: no tolerant
  adoption, no fuzzy fallback. A genuinely unreadable title (foil glare) never reads exactly,
  so the fallback tiers still serve it.
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
