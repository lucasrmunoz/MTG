# Flying and Reach altitude visuals

**Status: shipped** (2026-08-05) — this doc records the design. Standalone: pure rendering,
independent of scanning and of the game model.

## What it is

Cards with Flying render airborne in AR so it's obvious at a glance who is flying; Reach cards
show they can touch the air layer.

## Design

- **Flying** — the card's quad projects 5 cm (`FLY_HEIGHT_M`) along **world up** (gravity),
  not the pose's own up axis: a feature-point anchor's basis can face the camera, which turned
  the lift into a pure move-closer with no visible altitude (fixed 2026-08-19). Original design:
  with a translucent footprint shadow drawn at table level and a dashed tether line connecting
  shadow to card. Losing the shadow to a projection edge case loses only the shadow, never the
  card. Straight-down views project pure lift as nothing but a bigger card, so three cues make
  the hover read from any angle (2026-08-19): the altitude bobs ±1.2 cm on a slow sine
  (`FLY_BOB_M`), the shadow is pushed 2 cm sideways by a faked sun angle (`SHADOW_OFFSET_M`)
  and shrunk to 85%, and its edges are blur-softened (hard-edged below API 28, where hardware
  canvases ignore mask filters). A **Motion** pill in the AR control row stills the bob
  (persisted via activity SharedPreferences); the static lift plus offset shadow carry the
  read on their own.
- **Reach** — a dashed vertical line from the card up to flyer altitude, ending in a ring:
  "this card can touch what floats there."
- **Data source** — printed keywords (from Scryfall summaries; commanders inherit theirs from
  the art-version lookup) unioned with keyword counters. Adding a Flying counter in the panel
  lifts the card live; removing it lands the card. Flags are cached as volatile booleans on
  the card (`updateAbilityFlags`, UI thread) so the GL thread never touches the counter store.
- **Flying toggle** (added 2026-08-19) — printed Flying can be switched off and back on: the
  wheel's printed-Flying segment keeps its printed colour but answers taps (hub hint
  "Printed — release to toggle"), and the panel's Flying chip greys out while off and offers
  Disable/Enable in its glossary popup. A **Flying pill** on the card's chip row shows the
  state: normal chip colours with an orange outline while airborne, greyed with no outline
  when grounded (a Flying keyword counter folds into the pill instead of a second chip).
  The override is session-local (`ActiveCard.flyingDisabled`) — a lost ability is game
  state, so it is never persisted with the printing's counters. Reach is untouched.

Files: `ArCardActivity.java` (`FLY_HEIGHT_M`, `updateAbilityFlags`/`hasAbility`, `computePose`,
`projectCorners`), `CardOverlayView.java` (`CardPose` altitude fields, `drawGroundShadow`,
`drawReachIndicator`).

## Future extensions

- Other keyword visuals on the same mechanism (e.g. a subtle marker for deathtouch or
  first strike) — each is just another flag + draw call; add only what reads usefully at a
  real table.
