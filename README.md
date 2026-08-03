# MTG Card Lookup

Search Magic: The Gathering cards, browse every printing's artwork, and compare prices across
vendors. Card data comes from the [Scryfall API](https://scryfall.com/docs/api).

## What it does

- Search on part of a name and pick from the matches — "bolt" lists all 41 cards with it in the
  name, in any word order, and matching inside words too ("olt" finds Aether Revolt).
- Misspellings still land: a term no name contains falls back to fuzzy matching, so "snapcastr mage"
  finds Snapcaster Mage.
- See the full card: type line, mana cost, power/toughness or loyalty, keywords, oracle text, and
  which set and rarity the printing is.
- Browse every printing of that card that uses distinct artwork, oldest first, and pick one.
- Compare prices across Mana Pool, Card Kingdom and TCGplayer, per printing.
- Filter to foil or non-foil printings, and see foil prices.

## Structure

```
MTG.slnx
src/
  Mtg.Core/          Class library — card models and the Scryfall client
    Models/          Card, CardFace, ArtVersion, CardSearchResult
    Scryfall/        ScryfallClient, wire DTOs, mapper
  Mtg.Api/           ASP.NET Core minimal API
    Endpoints/       Card lookup endpoints
  frontend/          Next.js app (App Router, Tailwind v4)
    src/lib/         API client, pricing helpers, card helpers
    src/components/  SearchForm, SearchResults, CardDetail, ArtVersionGrid, PreviewPanel,
                     PriceControls
    android/         Capacitor Android project wrapping the static export into an APK
docs/
  HOW-TO-RUN.md
```

`Mtg.Core` has no dependency on ASP.NET — it is a plain library, so a console app or a test project
can use the same Scryfall client.

## API

Base URL `http://localhost:5000`. Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem
details.

| Method | Route | Returns |
|---|---|---|
| `GET` | `/api/cards/search?name=` | `200` matching cards and a total count, empty when nothing matches · `400` missing `name` |
| `GET` | `/api/cards/art?name=` | `200` art versions, empty array when the name matches nothing |
| `GET` | `/api/vendors` | `200` price vendors, their price basis, and whether each feed has loaded |

Search returns `{ "cards": [...], "totalMatches": n }`. `totalMatches` can exceed `cards.length` —
Scryfall pages at 175, and "a" matches over 25,000 cards — so the UI can say what it is not showing
rather than truncating silently.

When Scryfall itself is unreachable, both return `502` with a problem detail saying so — distinct from
an empty result meaning no card name matches.

The OpenAPI document is served at `/openapi/v1.json` in Development.

## Configuration

| Setting | Where | Default |
|---|---|---|
| Allowed CORS origins | `src/Mtg.Api/appsettings.json` → `Cors:AllowedOrigins` | `http://localhost:3000` |
| API base URL | `NEXT_PUBLIC_API_BASE_URL` env var for the frontend | `http://localhost:5000` |

## Prices

Three vendors, and they do **not** measure the same thing — which is why the UI always prints the
basis next to the vendor name, and says how current each one is.

| Vendor | Source | Price shown | Freshness |
|---|---|---|---|
| Mana Pool | `manapool.com/api/v1/products/singles` | Cheapest near-mint (`price_cents_nm`) | **Live** — fetched per request |
| Card Kingdom | `api.cardkingdom.com/api/v2/pricelist` | Cheapest near-mint (`nm_price`) | Cached — refreshed hourly |
| TCGplayer | Scryfall's `prices.usd` | **Market price** | Live per request; Scryfall refreshes daily |

Two vendors are queried live on every card lookup. `CardPricingService` collects the Scryfall
printing ids on screen and asks each live source for exactly those, in parallel; a vendor being
down is logged and its prices omitted rather than failing the lookup.

**Card Kingdom is the exception, and not by choice.** It publishes no per-card endpoint — only a
66 MB whole-catalogue download, which ignores query parameters like `?name=`. So it is the one
vendor served from a cached snapshot, refreshed hourly by `PriceRefreshService` and indexed by
Scryfall printing id. `GET /api/vendors` reports `live` and `fetchedAt` per vendor so the UI can
show "live" or "as of 12m ago" instead of implying everything is equally current.

Other things worth knowing:

- Coverage is partial, because both vendors list only what is **in stock**. A dash means "no price
  from this vendor", not "worthless".
- Card Kingdom's feed is undocumented and unsupported, and about 700 of its rows carry an unusable
  `scryfall_id`. Those rows are skipped and counted in the log rather than failing the whole load.
- Mana Pool's `scryfall_ids` is an array parameter: it must be repeated per value
  (`?scryfall_ids=a&scryfall_ids=b`). Comma-joining them returns a 400. Maximum 100 per request,
  so larger sets are chunked.
- TCGplayer has no condition breakdown to offer. Scryfall exposes a single figure per finish, so
  there is no cheapest-near-mint number to show; a real one would need a TCGplayer partner API key.

### Foil

Scryfall stores exactly one image per printing regardless of finish — there is no foil scan to
fetch. Selecting **Foil** filters to printings that exist in foil, shows foil prices, and renders the
normal image under a holographic sheen (`.foil-sheen` in `globals.css`) with a FOIL badge. That
sheen is a visual cue, not real foil artwork.

## Hosting

The frontend can run with no server at all. `src/frontend/src/lib/api.ts` picks its data source
from two env vars:

| Mode | When | Vendors |
|---|---|---|
| Through `Mtg.Api` | `NEXT_PUBLIC_API_BASE_URL` is set — `.env.development` sets it, so `next dev` uses it | All three |
| Android app | `NEXT_PUBLIC_MOBILE_APP=true` — set by `npm run app:build` | All three, fetched by the device itself |
| Straight to Scryfall | Neither is set, which is the default for a production build | TCGplayer only |

The serverless mode exists because **GitHub Pages serves static files only**, so `Mtg.Api` cannot run
there. What the browser can reach on its own decides the rest, and only Scryfall cooperates:

- **Scryfall** sends `access-control-allow-origin: *`, so card data, art versions and TCGplayer
  prices all work browser-direct.
- **Mana Pool** sends no `Access-Control-Allow-Origin` at all and its preflight returns 405, so a
  browser is hard-blocked. There is no client-side workaround in a browser.
- **Card Kingdom** does allow CORS, but its only endpoint is the 66 MB catalogue — every visitor
  would download it. Not viable on a public site.

The Android app escapes both constraints — see the next section.

## Android app

The same frontend ships as an installable APK via [Capacitor](https://capacitorjs.com/): the
static export runs in a WebView, and native access restores the two vendors the web-only build
loses, with no `Mtg.Api` behind it.

- **Mana Pool** is fetched live through Capacitor's native HTTP (`src/lib/sources/manaPool.ts`) —
  CORS does not exist outside the browser sandbox. Same chunking and id rules as
  `ManaPoolLiveSource`.
- **Card Kingdom**'s 66 MB catalogue is downloaded by the device *on demand* — the first time the
  vendor is picked in the dropdown, never at startup — then parsed off the stream row by row
  (`src/lib/sources/cardKingdom.ts`; one giant `JSON.parse` of 149k rows would eat hundreds of MB
  and can kill a phone WebView). Parsed prices live in **one cache file**, overwritten in place on
  every successful download — never a second copy. The cache counts as fresh for 24 hours;
  selecting the vendor after that re-downloads, and a **↻ Refresh** button next to the price
  picker replaces it on demand. If a re-download fails, the stale list keeps serving; the UI's
  "as of Xh ago" label says so.
- **TCGplayer** rides along on the Scryfall response, as everywhere.
- The random-card draw is enriched with the on-device vendors too, which even API mode cannot do.

`src/frontend/src/lib/sources/nativeApp.ts` composes these and mirrors `/api/vendors`' shape and
ordering, so the UI cannot tell the app apart from API mode. Build instructions:
[docs/HOW-TO-RUN.md](docs/HOW-TO-RUN.md#building-the-android-app-apk).

### AR: counters on physical cards

App-only (needs an [ARCore-supported device](https://developers.google.com/ar/devices)): **View in
AR** on a selected card opens the camera, finds that card on the table, and shows a zoomable
virtual copy carrying its counters. Native side in `android/…/mtg/ar/`, bridge in
`src/frontend/src/lib/ar.ts`.

- **Known images only.** Recognition is ARCore reference-image tracking against the card's own
  printings, registered at runtime from their Scryfall scans (63 mm physical width) — never "that
  rectangle looks like a card". Every art version rides along, so whichever copy you own is
  found; tapping a surface places the card manually when tracking can't lock (sleeve glare,
  low-detail full-art printings).
- **Cards join by themselves, several at once.** The scanner runs continuously: on-device OCR
  (ML Kit, bundled — hence most of the APK's size) reads title lines off the frames, each is
  confirmed through Scryfall's fuzzy lookup — which forgives OCR misreads like it forgives
  typos — and every confirmed card is registered and appears floating over its physical copy the
  moment tracking locks, no tap required. The collector line ("L 0195" / "SPM • EN") is read too
  and resolved to the *exact* printing, which is what gets basic lands right — "Island" matches
  hundreds of artworks, "SPM 195" is one card. Cards identified but not yet found by tracking
  wait as chips at the bottom: tap the chip, then tap a surface, to place one by hand. Physical
  token cards resolve like any card. Still known-cards-only: OCR merely decides which known card
  to register, nothing is guessed from shape.
- **Focus, floating, placed.** Every card floats upright above its physical copy by default.
  Tapping a card focuses it — the counter panel and pinch-zoom follow the focused card — and
  tapping the focused card lays it perspective-correct onto the physical card; tap again to lift
  it. Rendering is screen-space from the projected poses (no 3D engine); the camera feed is the
  standard ARCore GL background quad.
- **Counters.** Keyword counters (preset list + free text), stat counters in every sign
  combination (`+X/+X`, `-X/-X`, `+X/-X`, `-X/+X`, merged by kind with counts), and commander
  tax tracked as casts-from-command-zone (+2 each). Chips show on the virtual card; the panel
  edits them.
- **Counters survive.** State is keyed by printing id in one file (`ar-counters.json` in app
  storage, temp-file-renamed whole on every change — the Card Kingdom cache policy). Put the
  phone down, pick it up next game: recognising the card brings its counters back.

Tokens ("give me 13 zombies on the table") are the planned next phase and will need a real 3D
renderer; counters deliberately did not wait for that.

### Deploying to GitHub Pages

`.github/workflows/pages.yml` builds and publishes on every push to `main`. One-time setup:

1. Push this repo to GitHub as a **public** repo (Pages on a private repo needs GitHub Pro).
2. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
3. Push to `main`, or run the workflow manually from the Actions tab.

The site lands at `https://<you>.github.io/MTG/`. The base path is derived from the repo name at
build time, so renaming the repo changes the URL without a code edit.

To build the static export locally:

```powershell
cd src\frontend
$env:GITHUB_PAGES="true"; npm run build   # output in src/frontend/out
```

In Git Bash, prefix with `MSYS_NO_PATHCONV=1` or it rewrites the base path into a Windows path.

## Running it

```powershell
npm run setup   # first time only
npm run dev     # starts the API and the frontend together
```

Then open http://localhost:3000. Ctrl+C, or closing the terminal, stops both.

See [docs/HOW-TO-RUN.md](docs/HOW-TO-RUN.md) for running the two separately and for the
pre-commit checks.

## Origin

The card lookup, API and UI began in the `Abstract-Factory` repo, where they were built on top of an
Abstract Factory pattern exercise. This repo drops that scaffolding and keeps the MTG functionality.
In doing so it also fixes what the pattern had constrained:

- Keywords come from Scryfall's `keywords` field rather than substring-scanning oracle text, which had
  reported Vigilance on any card whose text merely contained the word.
- Double-faced cards show their artwork. The old code read only top-level `image_uris`, so cards like
  Delver of Secrets came back with no image at all.
- Card data is fetched asynchronously rather than blocking on `.Result` inside a constructor.
- "Card not found" and "Scryfall is down" are different outcomes rather than both becoming `null`.
- Cards are not forced into a creature-or-spell split.

## Notes

- `src/frontend/src/lib/decks.ts` is dead code, kept deliberately. The deck-building UI was removed
  — this is a card lookup, not a deck builder — but the colour definitions are retained for planned
  work on card colours. Delete it if that never happens.
- Scryfall asks that clients identify themselves and pace their requests. `ScryfallClient` sends a
  `User-Agent` and waits 100ms between paginated requests.
- Scryfall enforces that: it answers `400 generic_user_agent` to any request carrying an HTTP
  library's default User-Agent. That is why `images.unoptimized` is set in `next.config.ts` — Next's
  image optimiser fetches sources server-side with Node's default User-Agent, so every card image
  came back 400. Letting the browser load Scryfall's CDN directly avoids it. Do not remove that flag
  without solving the User-Agent problem first.
