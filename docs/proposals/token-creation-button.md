# Token creation from scanned cards

**Status: implemented** (2026-08-19, awaiting commit) — unit tests cover the count parsing
(`TokenCreationTest`) and the Scryfall field extraction (`ScryfallLookupTest`).

## What it is

Scanning a card whose text creates tokens — Army of the Damned's "Create thirteen tapped 2/2
black Zombie creature tokens." — offers a one-tap chip in its panel: **⚄ Create 13 × Zombie**.
Tapping it puts the official Zombie token card into the AR scene as a single *stack* showing
×13, placed with the existing tap-to-place flow, instead of the user scanning thirteen
physical tokens or the tokens being ignored entirely.

## How it identifies the token — no text parsing for the *what*

Scryfall's card JSON carries an `all_parts` array whose `component: "token"` entries link the
official token card (id, name) for every token a card creates. That is curated data, so which
token a card makes is never guessed from text; multi-token cards (a Treasure *and* Goblins)
get one create chip per token kind. `ScryfallLookup` now keeps `oracle_text` and the token
parts from the same responses the scanner already fetches — zero extra network calls at scan
time; the token card itself is fetched by id only when its chip is tapped.

## How it reads the count — `TokenCreation`

Oracle text spells counts out in words. Per sentence containing "create", the count word
nearest before the token's name wins: `a/an` = 1, `one`…`twenty`, plus bare numerals. This
handles compound sentences ("create two Goblins and a Treasure" answers each name with its
own number) and names appearing before the verb ("whenever a Zombie dies, create two…").
Open-ended counts — `create X`, `that many` — return 0 and the chip asks with a number picker
instead of guessing.

## The stack in the scene

- The stack is a normal `ActiveCard` keyed by the token card's printing id, so everything a
  scanned card can do works on it for free: tap-to-place, re-place, remove, focus, counters
  (+1/+1 on the whole zombie pile), keyword counters, Flying render.
- Its image registers as a reference image like any card's, so a physical token card on the
  table can pick up tracking — a bonus, not a requirement.
- The count shows as a **×13** chip over the card, in its panel and in the card list. Panel
  chips **−** / **×13** / **+** adjust it (the ×13 chip opens an exact-set picker); reaching
  zero removes the stack — the last zombie died.
- **The stack renders as its actual copies, dealt out.** The GL thread emits one pose per
  represented token — cascading rows of five centred on the anchor, each copy overlapping
  the last like cards laid on a table — so 13 zombies are 13 card images on the table, and
  a count change redraws the spread on the next frame. Copies past the first are *echo*
  poses: plain quads that join the stack's touch rect (tapping any copy focuses it) while
  the chips, focus border and the Flying shadow/tether stay on the primary alone. Rendered
  copies cap at 24 (`MAX_SPREAD_COPIES`) — past that the ×N chip carries the count, because
  99 warped bitmaps a frame cost more than a three-digit horde communicates.
- Tapping the create chip again (or a create that races a slow fetch) grows the existing
  stack; it never mints a second one.

## Decisions

- **Counts are session-local, never persisted.** A zombie horde is game state, not card
  state — unlike stat/keyword counters, which persist per printing by design.
- **Scan path only.** The single-card AR view opened from web search does not pass oracle
  text over the bridge; wiring the web side through the plugin is a follow-up if wanted.
- **0 means ask.** Any count the parser cannot settle falls back to a number picker with no
  default guess.
