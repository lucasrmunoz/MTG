# MTG Card Lookup

Search Magic: The Gathering cards, browse every printing's artwork, and compare prices across
vendors. Card data comes from the [Scryfall API](https://scryfall.com/docs/api).

## What it does

- Search any card by name, tolerating misspellings — "snapcastr mage" finds Snapcaster Mage.
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
    Models/          Card, CardFace, ArtVersion
    Scryfall/        ScryfallClient, wire DTOs, mapper
  Mtg.Api/           ASP.NET Core minimal API
    Endpoints/       Card lookup endpoints
  frontend/          Next.js app (App Router, Tailwind v4)
    src/lib/         API client, pricing helpers, card helpers
    src/components/  SearchForm, CardDetail, ArtVersionGrid, PreviewPanel, PriceControls
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
| `GET` | `/api/cards/search?name=` | `200` the card · `404` no such card · `400` missing `name` |
| `GET` | `/api/cards/art?name=` | `200` art versions, empty array when the name matches nothing |
| `GET` | `/api/vendors` | `200` price vendors, their price basis, and whether each feed has loaded |

When Scryfall itself is unreachable, both return `502` with a problem detail saying so — distinct from
a `404` meaning the card genuinely does not exist.

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

The frontend can run with no server at all. `src/frontend/src/lib/api.ts` picks its data source from
`NEXT_PUBLIC_API_BASE_URL`:

| Mode | When | Vendors |
|---|---|---|
| Through `Mtg.Api` | `NEXT_PUBLIC_API_BASE_URL` is set — `.env.development` sets it, so `next dev` uses it | All three |
| Straight to Scryfall | The variable is unset, which is the default for a production build | TCGplayer only |

The serverless mode exists because **GitHub Pages serves static files only**, so `Mtg.Api` cannot run
there. What the browser can reach on its own decides the rest, and only Scryfall cooperates:

- **Scryfall** sends `access-control-allow-origin: *`, so card data, art versions and TCGplayer
  prices all work browser-direct.
- **Mana Pool** sends no `Access-Control-Allow-Origin` at all and its preflight returns 405, so a
  browser is hard-blocked. There is no client-side workaround.
- **Card Kingdom** does allow CORS, but its only endpoint is the 66 MB catalogue — every visitor
  would download it. Not viable.

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
