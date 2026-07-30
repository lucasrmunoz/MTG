/**
 * Picks where card data comes from.
 *
 * With NEXT_PUBLIC_API_BASE_URL set, requests go through this project's Mtg.Api, which adds Mana
 * Pool and Card Kingdom prices. Without it, the browser talks to Scryfall directly and only
 * TCGplayer prices are available — that is the mode a static host like GitHub Pages runs in, since
 * there is no server there to run the API.
 *
 * `.env.development` sets the variable, so `next dev` uses the API while a production build
 * defaults to the serverless path.
 */

import * as scryfall from "@/lib/sources/scryfall";
import { createClient } from "@/lib/sources/mtgApi";

const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;

const source =
  apiBaseUrl !== undefined && apiBaseUrl !== "" ? createClient(apiBaseUrl) : scryfall;

/** True when running against Scryfall directly, so only TCGplayer prices exist. */
export const usingDirectScryfall = apiBaseUrl === undefined || apiBaseUrl === "";

/** Finds one card by name, tolerating misspellings. */
export const searchCard = source.searchCard;

/** Lists every printing of a card with distinct artwork, oldest first. */
export const fetchArtVersions = source.fetchArtVersions;

/** Lists price vendors and whether each one's data is live. */
export const fetchVendors = source.fetchVendors;

export { ApiError } from "@/lib/errors";
