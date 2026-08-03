import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.lucasmunoz.mtg",
  appName: "MTG Card Lookup",
  // The Next static export produced by `npm run app:build`.
  webDir: "out",
  plugins: {
    // Android 15+ forces edge-to-edge, so the WebView extends under the system bars. "css" makes
    // Capacitor inject --safe-area-inset-* variables, which globals.css turns into body padding.
    SystemBars: {
      insetsHandling: "css",
      style: "DARK",
    },
  },
};

export default config;
