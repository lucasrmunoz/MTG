# App identity and build hardening

**Status: shipped** (2026-08-05) — this doc records the decisions. Standalone.

## Naming

- The Android app is **MTG Combat AR** (launcher label, splash, Capacitor `appName`).
- The web keeps **MTG Card Lookup** branding — it has no AR, so it doesn't claim it. Only the
  meta description gained "track Commander games".
- Package id `com.lucasmunoz.mtg` is unchanged on purpose: installs upgrade in place instead
  of appearing as a second app.

## Build

- **arm64-only native libs** (`ndk.abiFilters` in `app/build.gradle`): every ARCore-capable
  phone is arm64; the other ABIs only padded the APK (ML Kit alone ships ~11 MB per ABI).
- **Scanner thread isolation** (`scanExecutor` in `ArCardActivity`): an adopted card queues
  minutes of reference-image downloads and ARCore database rebuilds on the main executor;
  scanning runs on its own thread so those can never starve the next scan pass.
