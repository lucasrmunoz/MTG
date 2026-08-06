# Full-card scanning with artwork identification

**Status: phase 0 tooling in place** — the in-app corpus recorder (debug builds, the ⏺ Corpus
toggle in the AR screen) and the desktop replay harness (`tools/scan-harness/ReplayHarness.java`)
exist; the ≥50-frame table corpus is still to be captured. The full pipeline was attempted
2026-08-05 (commit `9aaf5b1`) and reverted the same day. This
is ONE proposal, not several: banded OCR needs a detected quad to warp, artwork ranking needs
the warped image to hash, and the overlay-stability fallback needs per-frame quads. Build it
in phase order or not at all.

## Motivation

The shipped text-line scanner reads titles anywhere in frame, so rules text can become a
phantom card, and collector numbers pair with set codes frame-wide, so two cards in frame can
mint a card neither of them is. Mythic Tools' full-card-box scanning avoids both classes of
error. Today the mitigation is the shipped scan-correction tools (✕ / Rescan); this proposal
is the structural fix.

## Why the first attempt failed — and the two rules it produced

The pipeline worked end to end (detection → warp → banded OCR → Scryfall → artwork hash), but
every threshold shipped blind and was "tuned" through full sideload-and-table-test rounds:
contour detection missed cards on dark surfaces and rounded sleeves, and the artwork gate
rejected genuinely correct cards — photographed-card vs. Scryfall-scan dHash distances under
real lighting routinely exceed any safe cutoff.

1. **CV confidence scores rank, they never veto.** Artwork hashing may choose which printing
   joins; it must never block a Scryfall-confirmed card.
2. **No camera threshold ships without a corpus.** Every constant (edge sensitivity, minimum
   area, corner epsilon, hash distance) is tuned offline against captured frames from the real
   table before any APK build.

## Phase 0 — capture and calibration harness (the prerequisite the first attempt skipped)

A debug affordance in the AR screen that saves the camera's Y-plane frames (with
width/stride metadata) to app files during a normal session at the actual play table — dark
mat, sleeves, evening light. A desktop harness (plain JVM + desktop OpenCV) replays the corpus
through the detector so tuning happens in seconds, offline.
**Acceptance:** ≥50 frames covering the surfaces and lighting actually played on.

## Phase 1 — card-quad detection

OpenCV contours → convex 4-gons at card aspect (63:88). Keep the two robustness passes the
first attempt added late: an adaptive-threshold fallback when global edge detection finds
nothing (dark border on dark surface), and a looser corner-approximation retry (sleeved
cards' rounded corners). Green outline = the detected full card.
**Acceptance:** detects a lone card in ≥90% of corpus frames before any phone build.
**Cost:** `org.opencv:opencv` 5.x, ~15 MB arm64. Gotcha: in OpenCV 5, contour geometry
(`contourArea`, `approxPolyDP`, `arcLength`, `isContourConvex`, `getPerspectiveTransform`)
lives in `org.opencv.geometry.Geometry`, not `Imgproc`.

## Phase 2 — per-quad banded OCR

Each quad perspective-warped flat (~384×536). Title read from the top band, collector line
from the bottom band, paired within that one card — never frame-wide. A 180° retry reads
cards facing an opponent. This is what structurally kills phantom cards and cross-card
pairing. Run OCR synchronously on the scan thread (`Tasks.await`), never on the GL thread.

## Phase 3 — artwork ranking for exact printings

dHash (64-bit difference hash) of the warped card against the candidate's printings, cached
per printing id, art-version listings cached per name. Adopt the closest printing — ranking
only, per rule 1. Matches under a corpus-calibrated confidence bar may displace a fuzzy-name
printing choice; distant ones still join as the fuzzy candidate. During calibration, surface
the distance on screen — the first attempt's silent rejections cost a day.

## Phase 4 (optional) — quads as overlay-stability fallback

With per-frame quads available, keep a card's overlay glued to its detected quad when ARCore
image tracking momentarily drops — a 2D screen-space fallback. Improves floating-card and
counter-chip stability. Does not affect life tokens (plane-anchored, separate system).
