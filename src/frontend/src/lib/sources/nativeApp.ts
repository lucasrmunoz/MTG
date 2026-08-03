/**
 * Data source for the Android app build (NEXT_PUBLIC_MOBILE_APP=true).
 *
 * Card data comes straight from Scryfall, exactly as in the static-web build. Prices go further
 * than a browser can: Mana Pool is fetched live over native HTTP (CORS does not exist on the
 * native side), and Card Kingdom's catalogue is downloaded to the device on demand and cached as
 * a single file. TCGplayer rides along on the Scryfall response as always. Vendor ids and shapes
 * mirror Mtg.Api's /api/vendors, so the UI cannot tell this mode from API mode.
 */

import type { ColorMatchMode } from "@/lib/colors";
import * as cardKingdom from "@/lib/sources/cardKingdom";
import * as manaPool from "@/lib/sources/manaPool";
import * as scryfall from "@/lib/sources/scryfall";
import type { ArtVersion, Card, CardSearchResult, VendorInfo, VendorPrice } from "@/lib/types";

/** Vendor ids as Mtg.Api advertises them; every prices dictionary is keyed by these. */
const MANA_POOL = "manaPool";
const CARD_KINGDOM = "cardKingdom";

interface Priced {
  id: string;
  prices: Record<string, VendorPrice>;
}

/** Merges the cached Card Kingdom prices into already-fetched cards or art versions. */
export function applyCachedPrices<T extends Priced>(items: T[]): T[] {
  if (!cardKingdom.isLoaded()) {
    return items;
  }

  return items.map((item) => {
    const price = cardKingdom.priceFor(item.id);
    return price === undefined
      ? item
      : { ...item, prices: { ...item.prices, [CARD_KINGDOM]: price } };
  });
}

/**
 * Attaches Mana Pool live prices plus any cached Card Kingdom prices. A vendor being unreachable
 * must not fail the card lookup, so its prices are simply omitted — Mtg.Api's policy exactly.
 */
async function enrich<T extends Priced>(items: T[]): Promise<T[]> {
  if (items.length === 0) {
    return items;
  }

  let live = new Map<string, VendorPrice>();
  try {
    live = await manaPool.fetchPrices(items.map((item) => item.id));
  } catch (err) {
    console.warn("Mana Pool price lookup failed; continuing without its prices.", err);
  }

  return applyCachedPrices(items).map((item) => {
    const price = live.get(item.id.toLowerCase());
    return price === undefined
      ? item
      : { ...item, prices: { ...item.prices, [MANA_POOL]: price } };
  });
}

export async function searchCards(name: string): Promise<CardSearchResult> {
  const result = await scryfall.searchCards(name);
  return { ...result, cards: await enrich(result.cards) };
}

export async function fetchArtVersions(name: string): Promise<ArtVersion[]> {
  return enrich(await scryfall.fetchArtVersions(name));
}

export async function fetchRandomCard(colors: string[], mode: ColorMatchMode): Promise<Card> {
  const card = await scryfall.fetchRandomCard(colors, mode);
  return (await enrich([card]))[0] ?? card;
}

/** Mirrors VendorCatalog's ordering: live vendors first, then cached feeds, then TCGplayer. */
export function fetchVendors(): Promise<VendorInfo[]> {
  return Promise.resolve([
    {
      id: MANA_POOL,
      name: "Mana Pool",
      priceBasis: "NM",
      live: true,
      fetchedAt: null,
      loaded: true,
    },
    {
      id: CARD_KINGDOM,
      name: "Card Kingdom",
      priceBasis: "NM",
      live: false,
      fetchedAt: cardKingdom.getFetchedAt(),
      loaded: cardKingdom.isLoaded(),
    },
    {
      id: "tcgplayer",
      name: "TCGplayer",
      priceBasis: "market",
      live: true,
      fetchedAt: null,
      loaded: true,
    },
  ]);
}

/**
 * Loads a cached vendor's catalogue if it is missing or stale. On demand: the page calls this
 * when the user selects the vendor, never at startup, so the 66 MB download never runs unasked.
 */
export async function ensureVendorLoaded(vendorId: string): Promise<void> {
  if (vendorId === CARD_KINGDOM) {
    await cardKingdom.ensureLoaded();
  }
}

/** Forces a fresh catalogue download right now, replacing the single cached copy. */
export async function refreshVendor(vendorId: string): Promise<void> {
  if (vendorId === CARD_KINGDOM) {
    await cardKingdom.refresh();
  }
}
