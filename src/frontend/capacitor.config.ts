import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.lucasmunoz.mtg",
  appName: "MTG Combat AR",
  // The Next static export produced by `npm run app:build`.
  webDir: "out",
  server: {
    // Serve the WebView from http://localhost instead of Capacitor's default https://localhost.
    // localhost is a trustworthy origin either way — secure-context APIs like Wake Lock keep
    // working — but the cryptographic scheme forbids the plain ws:// connection LAN session
    // testing uses (mixed content). A hosted relay will use wss:// and works from both schemes.
    androidScheme: "http",
  },
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
