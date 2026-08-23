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

## Trying shared game sessions on the LAN

The commander game can be shared live: the app hosts, other phones join by QR code or by typing
the 6-character code at `/game` on the web build. The relay is part of Mtg.Api
(`/api/sessions/ws`), so nothing extra runs — but one rule governs everything below:

> **Every device — the hosting phone and every guest — must reach the *same* Mtg.Api process.**
> Sessions live in memory in that one process, so a code created through one PC's relay does not
> exist on another PC's. Two machines both "running the latest" each have their own empty relay;
> host and guests have to meet at one of them.

That is why the URLs below use the PC's wifi IP, never `localhost` (which on a phone means the
phone itself), and why the phone app must be rebuilt when the relay PC changes — its URLs are
baked in at APK build time.

### 1. Pick the relay PC and find its IP

Exactly one PC runs the stack for the whole table. On it, `ipconfig` shows the wifi IPv4 address
(e.g. `192.168.1.20`) — every command below uses that address; substitute your own.

### 2. Start the API on all interfaces

```powershell
cd d:\SoftwareProjects\MTG
dotnet run --project src/Mtg.Api --urls http://0.0.0.0:5000
```

The default `dotnet run` binds `localhost` only; `--urls http://0.0.0.0:5000` is what lets phones
connect. Windows Firewall may prompt to allow `dotnet` (and `node` below) on private networks the
first time — allow both, or phones connect to nothing and joins time out.

Sanity check from a phone on the wifi before going further: open
`http://192.168.1.20:5000/openapi/v1.json` in its browser. JSON means the phone can reach the
relay PC; anything else is an IP or firewall problem — fix that first.

### 3. Start the frontend with LAN URLs

```powershell
cd d:\SoftwareProjects\MTG\src\frontend
$env:NEXT_PUBLIC_SESSION_WS_URL="ws://192.168.1.20:5000/api/sessions/ws"
$env:NEXT_PUBLIC_WEB_APP_URL="http://192.168.1.20:3000"
$env:NEXT_PUBLIC_API_BASE_URL=""   # guests talk to Scryfall directly; avoids a CORS entry
npm run dev
```

Without these overrides, `.env.development` points guests at `ws://localhost:5000` — each guest's
own machine, not the relay — which is exactly the "No game with that code was found" trap. Set
them in the same terminal that runs `npm run dev`; they are read at startup.

### 4. Rebuild the APK so the phone hosts through this PC

The phone app's two URLs are baked in at build time (static export). An APK built for another
machine — or built without these variables at all — cannot host through this PC, no matter how
current its code is. Build and reinstall:

```powershell
cd d:\SoftwareProjects\MTG\src\frontend
$env:NEXT_PUBLIC_SESSION_WS_URL="ws://192.168.1.20:5000/api/sessions/ws"
$env:NEXT_PUBLIC_WEB_APP_URL="http://192.168.1.20:3000"
$env:JAVA_HOME="$env:LOCALAPPDATA\Android\jdk21"
npm run app:apk
```

### 5. Host and join

On the phone app, start sharing from the commander game — it shows the QR code and the
6-character code. Guests on the same wifi scan the QR (its link carries the code and joins on
arrival) or open `http://192.168.1.20:3000/game` and type the code.

Hosting ships only in the app build, so the browser's `/game` is always the guest door. To try the
host flow in a browser anyway, run a second dev server with `NEXT_PUBLIC_MOBILE_APP=true` (e.g. on
`--port 3001`): the game tracker and sharing work; card search in that tab does not, because app
mode expects the on-device data plugins.

### If join fails

The error on the join page says which step broke:

| Symptom | Cause | Fix |
|---|---|---|
| "No game with that code was found." | Guest reached a relay, but not the one the host is on — usually a guest page served without the step 3 overrides, or an APK baked for a different PC | Redo steps 3–4 against the one relay PC |
| "Could not reach the session server." | The guest's ws URL points at an API that is not running, or a stale IP | Restart step 2; re-check `ipconfig` (DHCP moves IPs) |
| "The session server did not answer in time." | Reachable address, silently dropped — almost always Windows Firewall | Allow `dotnet`/`node` on private networks |
| No join panel at all on `/game` | `NEXT_PUBLIC_SESSION_WS_URL` was unset when the page was built/served | Step 3 (dev) or rebuild (APK); sharing hides entirely without it |
| Phone cannot start sharing | The APK's baked ws URL does not resolve from the phone | Step 4 with this PC's current IP |

Sessions are in memory only, so restarting Mtg.Api forgets every code — hosts just share again
for a new one. A later hosted deployment (one stable relay URL) removes the per-machine rebuild;
until then, the baked IP ties an APK to one relay PC.

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
