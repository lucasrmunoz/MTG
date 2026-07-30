import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";

// Next infers the workspace root from the nearest lockfile walking upwards, which would find the
// repo-root package-lock.json that exists only to launch the API and this app together. That root
// has no node_modules, so module resolution fails. Pin the root to this directory instead.
const projectRoot = dirname(fileURLToPath(import.meta.url));

// GitHub Pages serves a project repo from a subpath, so the build for it needs a static export and
// a base path. Gated on an env var the deploy workflow sets, so local dev and a plain `npm run
// build` are unaffected and keep serving from the root.
const forGitHubPages = process.env.GITHUB_PAGES === "true";
const basePath = process.env.PAGES_BASE_PATH ?? "/MTG";

const nextConfig: NextConfig = {
  ...(forGitHubPages ? { output: "export" as const, basePath, assetPrefix: basePath } : {}),
  turbopack: {
    root: projectRoot,
  },
  outputFileTracingRoot: projectRoot,
  images: {
    // Next's image optimiser fetches the source server-side with Node's default User-Agent, and
    // Scryfall rejects those outright ("generic_user_agent", HTTP 400) — so every optimised image
    // 400s. Next exposes no way to set headers on that fetch. Serving Scryfall's URLs straight to
    // the browser sidesteps it: the browser sends a real User-Agent, and Scryfall's CDN already
    // returns pre-sized, cached variants (normal, art_crop), so there is little left to optimise.
    // Also required by `output: "export"`, which has no server to optimise on.
    unoptimized: true,
    remotePatterns: [
      {
        protocol: "https",
        hostname: "cards.scryfall.io",
        pathname: "/**",
      },
    ],
  },
};

export default nextConfig;
