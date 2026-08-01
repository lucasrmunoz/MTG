# How to run the MTG Deck Builder

## Prerequisites

- .NET SDK 10
- Node.js 22

## One command

```powershell
cd d:\SoftwareProjects\MTG
npm run dev
```

Starts the API and the frontend together, with each line of output tagged `[api]` or `[web]`. Open
**http://localhost:3000**.

First time on a fresh clone, install dependencies for both first:

```powershell
npm run setup
```

### Stopping

**Ctrl+C**, or just close the terminal. Both services stop together — including the `Mtg.Api.exe`
process that `dotnet run` spawns underneath itself, which is the one that would otherwise linger and
hold port 5000. If either service exits on its own, the other is stopped too, so you never end up
with half the stack running.

## Running them separately

Useful when you want to restart one service without bouncing the other, or want its logs on their own.
Two terminals, API first:

```powershell
# Terminal 1
cd d:\SoftwareProjects\MTG
dotnet run --project src/Mtg.Api

# Terminal 2
cd d:\SoftwareProjects\MTG\src\frontend
npm run dev
```

Start the API first. The frontend calls it from the browser, so if it is down every search reports
that the card API could not be reached.

The OpenAPI document is at `http://localhost:5000/openapi/v1.json`.

## Checks before committing

```powershell
# From d:\SoftwareProjects\MTG
dotnet build -warnaserror

# From d:\SoftwareProjects\MTG\src\frontend
npm run typecheck
npm run lint
npm run build
npm audit --audit-level=moderate
```

Stop `npm run dev` first — the frontend build and the dev server both write to `.next`.

`npm audit` currently reports one unfixed dev-only advisory in eslint's dependency tree; see the
`comments.knownUnfixed` note in `src/frontend/package.json` for why neither available fix is viable
yet.

## Trying the API directly

```powershell
curl "http://localhost:5000/api/cards/search?name=Lightning%20Bolt"
curl "http://localhost:5000/api/cards/art?name=Lightning%20Bolt"

# Partial name — 41 matches, exact name first
curl "http://localhost:5000/api/cards/search?name=bolt"

# Words in any order
curl "http://localhost:5000/api/cards/search?name=bolt%20light"

# More matches than one page — totalMatches 25818, cards 175
curl "http://localhost:5000/api/cards/search?name=a"

# Misspelling — no name contains it, so it falls back to fuzzy matching
curl "http://localhost:5000/api/cards/search?name=snapcastr%20mage"

# Double-faced card — returns an image plus both faces
curl "http://localhost:5000/api/cards/search?name=Delver%20of%20Secrets"

# Unknown card — 200 with an empty cards array
curl "http://localhost:5000/api/cards/search?name=asdfqwerzxcv"

# Missing name — 400 problem details
curl "http://localhost:5000/api/cards/search"
```

## Building the hosted (static) version

The GitHub Pages build has no API behind it and talks to Scryfall directly, so only TCGplayer
prices are available. To produce it locally:

```powershell
cd d:\SoftwareProjects\MTG\src\frontend
$env:GITHUB_PAGES="true"; npm run build   # output in src/frontend/out
```

From Git Bash, prefix the command with `MSYS_NO_PATHCONV=1` — otherwise it rewrites the base path
into a Windows path and the build fails.

Deployment is automatic on push to `main` via `.github/workflows/pages.yml`. See the Hosting section
of the README for the one-time repository settings.

## Ports

| Service | URL | Purpose |
|---|---|---|
| Frontend | http://localhost:3000 | Deck builder UI |
| API | http://localhost:5000 | Card lookup |

To change the API port, edit `src/Mtg.Api/Properties/launchSettings.json`, then update
`Cors:AllowedOrigins` in `appsettings.json` if the frontend port changes too, and point the frontend
at the new API port with `NEXT_PUBLIC_API_BASE_URL`.
