/**
 * Picks where card data comes from. Three modes:
 *
 * - Mtg.Api (NEXT_PUBLIC_API_BASE_URL set): requests go through this project's API, which adds
 *   Mana Pool and Card Kingdom prices. `.env.development` sets the variable, so `next dev` runs
 *   in this mode.
 * - Android app (NEXT_PUBLIC_MOBILE_APP=true): the browser talks to Scryfall directly and the
 *   device fills in the other vendors itself — Mana Pool live over native HTTP, Card Kingdom from
 *   an on-device cached catalogue.
 * - Static web (neither set): the browser talks to Scryfall directly and only TCGplayer prices
 *   are available. This is what a static host like GitHub Pages runs, since there is no server
 *   there to run the API.
 */

import { createClient } from "@/lib/sources/mtgApi";
import * as nativeApp from "@/lib/sources/nativeApp";
import * as scryfall from "@/lib/sources/scryfall";

const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;
const isMobileApp = process.env.NEXT_PUBLIC_MOBILE_APP === "true";

const source = isMobileApp
  ? nativeApp
  : apiBaseUrl !== undefined && apiBaseUrl !== ""
    ? createClient(apiBaseUrl)
    : scryfall;

/** Finds every card whose name contains the search term, best match first. */
export const searchCards = source.searchCards;

/** Lists every printing of a card with distinct artwork, oldest first. */
export const fetchArtVersions = source.fetchArtVersions;

/** Lists price vendors and whether each one's data is live. */
export const fetchVendors = source.fetchVendors;

/**
 * A random card, optionally constrained by color. Always answered by Scryfall directly — its
 * random endpoint is CORS-open in every mode, and Mtg.Api publishes no random-card route. The
 * app build enriches the draw with its on-device vendor prices; elsewhere the card carries
 * TCGplayer prices only.
 */
export const fetchRandomCard = isMobileApp ? nativeApp.fetchRandomCard : scryfall.fetchRandomCard;

/**
 * Controls for vendors whose prices live in an on-device cached catalogue — the app's Card
 * Kingdom list. Null outside the app build, where no such cache exists, so the UI hides its
 * refresh affordances.
 */
export const cachedVendors = isMobileApp
  ? {
      /** Loads the vendor's catalogue if missing or stale. Called when the vendor is selected. */
      ensure: nativeApp.ensureVendorLoaded,
      /** Re-downloads the catalogue right now, replacing the single cached copy. */
      refresh: nativeApp.refreshVendor,
      /** Merges cached prices into already-fetched items after a load or refresh. */
      apply: nativeApp.applyCachedPrices,
    }
  : null;

export { ApiError } from "@/lib/errors";
