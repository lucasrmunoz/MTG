# How to run MTG Card Lookup

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

## Building the Android app (APK)

The frontend also builds into an installable Android app via Capacitor — see the "Android app"
section of the README for what it does differently. Toolchain (no Android Studio needed):

| Piece | Where |
|---|---|
| Android SDK (cmdline-tools, platform-tools; Gradle fetches the rest) | `%LOCALAPPDATA%\Android\Sdk` |
| Temurin JDK 21 (Gradle needs it; the system Java 17 is too old) | `%LOCALAPPDATA%\Android\jdk21` |

`src/frontend/android/local.properties` (gitignored, machine-specific) points Gradle at the SDK.
Build:

```powershell
cd d:\SoftwareProjects\MTG\src\frontend
$env:JAVA_HOME="$env:LOCALAPPDATA\Android\jdk21"
npm run app:apk
```

That chains three steps, also available separately:

- `npm run app:build` — the static export with `NEXT_PUBLIC_MOBILE_APP=true`, into `out/`
- `npm run app:sync` — the above, then `cap sync android` copies it into the Android project
- `npm run app:apk` — the above, then Gradle's `assembleDebug`

The APK lands at `src/frontend/android/app/build/outputs/apk/debug/app-debug.apk`. Install it by
copying it to the phone and opening it (allow "install from unknown sources"), or over USB with
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb install app-debug.apk`.

It is a debug-signed build, fine for installing on your own phone. The first Gradle run downloads
the Android platform and build-tools it needs, so it takes several minutes; later builds are fast.

### AR requirements

The **View in AR** feature additionally needs, on the phone:

- An [ARCore-supported device](https://developers.google.com/ar/devices).
- **Google Play Services for AR** — the AR screen prompts to install it on first use.
- Camera permission, requested on first use. The app itself installs and runs fine without any of
  this; only the AR screen is affected.

Android unit tests (counter model and store):

```powershell
cd d:\SoftwareProjects\MTG\src\frontend\android
$env:JAVA_HOME="$env:LOCALAPPDATA\Android\jdk21"
.\gradlew :app:testDebugUnitTest
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
