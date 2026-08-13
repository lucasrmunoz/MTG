/**
 * Browser-direct Scryfall client.
 *
 * Used when no Mtg.Api instance is configured — notably on GitHub Pages, which serves static files
 * and cannot run the API. Scryfall sends `access-control-allow-origin: *`, so the browser can call
 * it straight. Card Kingdom and Mana Pool are unavailable in this mode: Mana Pool sends no CORS
 * header at all, and Card Kingdom publishes only a 66 MB whole-catalogue download.
 *
 * The mapping here mirrors Mtg.Core's ScryfallCardMapper. Keep the two in step.
 */

import type { ColorMatchMode } from "@/lib/colors";
import { ApiError } from "@/lib/errors";
import type {
  ArtVersion,
  Card,
  CardFace,
  CardSearchResult,
  VendorInfo,
  VendorPrice,
} from "@/lib/types";

const SCRYFALL = "https://api.scryfall.com";

/** Scryfall asks for 50-100ms between requests from a single client. */
const PAGE_DELAY_MS = 100;

/** Safety bound on pagination, matching ScryfallClient.MaxArtPages. */
const MAX_ART_PAGES = 10;

interface ScryfallImageUris {
  normal?: string;
  art_crop?: string;
}

interface ScryfallFace {
  name?: string;
  mana_cost?: string;
  type_line?: string;
  oracle_text?: string;
  power?: string;
  toughness?: string;
  image_uris?: ScryfallImageUris;
}

interface ScryfallCard {
  id: string;
  name?: string;
  mana_cost?: string;
  cmc?: number;
  type_line?: string;
  oracle_text?: string;
  power?: string;
  toughness?: string;
  loyalty?: string;
  colors?: string[];
  color_identity?: string[];
  keywords?: string[];
  rarity?: string;
  set?: string;
  set_name?: string;
  collector_number?: string;
  artist?: string;
  released_at?: string;
  finishes?: string[];
  prices?: { usd?: string | null; usd_foil?: string | null };
  image_uris?: ScryfallImageUris;
  card_faces?: ScryfallFace[];
}

interface ScryfallList {
  total_cards?: number;
  has_more?: boolean;
  next_page?: string;
  data?: ScryfallCard[];
}

async function getJson<T>(url: string, operation: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url);
  } catch {
    throw new ApiError(`Could not reach Scryfall during ${operation}.`, 0);
  }

  if (response.status === 404) {
    throw new ApiError(`No card matches that name. Check the spelling and try again.`, 404);
  }

  if (!response.ok) {
    throw new ApiError(`Scryfall returned ${response.status} during ${operation}.`, response.status);
  }

  return (await response.json()) as T;
}

/**
 * Transform and modal double-faced cards carry no top-level image_uris — their scans hang off each
 * entry in card_faces. Falling back to the front face is what makes cards like Delver of Secrets
 * show a picture at all.
 */
function firstFaceImages(card: ScryfallCard): ScryfallImageUris | undefined {
  return card.card_faces?.find((face) => face.image_uris !== undefined)?.image_uris;
}

function imageUrl(card: ScryfallCard): string | null {
  return card.image_uris?.normal ?? firstFaceImages(card)?.normal ?? null;
}

function artCropUrl(card: ScryfallCard): string | null {
  return card.image_uris?.art_crop ?? firstFaceImages(card)?.art_crop ?? null;
}

/** Scryfall quotes prices as decimal strings and omits them entirely when unknown. */
function parsePrice(value: string | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function tcgplayerPrices(card: ScryfallCard): Record<string, VendorPrice> {
  const price: VendorPrice = {
    nonfoil: parsePrice(card.prices?.usd),
    foil: parsePrice(card.prices?.usd_foil),
  };

  if (price.nonfoil === null && price.foil === null) {
    return {};
  }

  return { tcgplayer: price };
}

function toFace(face: ScryfallFace): CardFace {
  return {
    name: face.name ?? "",
    manaCost: face.mana_cost ?? "",
    typeLine: face.type_line ?? "",
    oracleText: face.oracle_text ?? "",
    power: face.power ?? null,
    toughness: face.toughness ?? null,
    imageUrl: face.image_uris?.normal ?? null,
  };
}

function toCard(source: ScryfallCard): Card {
  return {
    id: source.id,
    name: source.name ?? "",
    manaCost: source.mana_cost ?? "",
    manaValue: source.cmc ?? 0,
    typeLine: source.type_line ?? "",
    oracleText: source.oracle_text ?? "",
    power: source.power ?? null,
    toughness: source.toughness ?? null,
    loyalty: source.loyalty ?? null,
    colors: source.colors ?? [],
    colorIdentity: source.color_identity ?? [],
    keywords: source.keywords ?? [],
    rarity: source.rarity ?? "",
    setCode: source.set ?? "",
    setName: source.set_name ?? "",
    imageUrl: imageUrl(source),
    finishes: source.finishes ?? [],
    prices: tcgplayerPrices(source),
    faces: source.card_faces?.map(toFace) ?? [],
  };
}

/** Returns null when the printing has no usable scan, since art versions exist to be looked at. */
function toArtVersion(source: ScryfallCard): ArtVersion | null {
  const image = imageUrl(source);
  if (image === null) {
    return null;
  }

  return {
    id: source.id,
    setCode: source.set ?? "",
    setName: source.set_name ?? "Unknown Set",
    collectorNumber: source.collector_number ?? "",
    artist: source.artist ?? "Unknown Artist",
    releasedAt: source.released_at ?? null,
    imageUrl: image,
    artCropUrl: artCropUrl(source),
    finishes: source.finishes ?? [],
    prices: tcgplayerPrices(source),
  };
}

/**
 * Turns a search term into a Scryfall query of ANDed `name:` clauses, one per word.
 *
 * Each word is quoted so punctuation carries through — `name:"Urza's"` works, and a leading hyphen
 * reads as part of the name rather than as Scryfall's negation operator. Quotes and backslashes are
 * stripped instead of escaped: Scryfall's parser has no escape sequence for them inside quotes.
 *
 * Returns null when the term holds nothing searchable. Mirrors ScryfallClient.BuildNameQuery.
 */
function buildNameQuery(term: string): string | null {
  const clauses = term
    .split(/\s+/)
    .map((word) => word.replaceAll('"', "").replaceAll("\\", ""))
    .filter((word) => word !== "")
    .map((word) => `name:"${word}"`);

  return clauses.length === 0 ? null : clauses.join(" ");
}

/**
 * Orders matches by how closely the whole name tracks the term, so searching a card's full name
 * puts that card first rather than alphabetically among its partial matches.
 */
function matchRank(cardName: string, term: string): number {
  const name = cardName.toLowerCase();
  const wanted = term.toLowerCase();

  if (name === wanted) {
    return 0;
  }

  return name.startsWith(wanted) ? 1 : 2;
}

/**
 * Scryfall's fuzzy name lookup, which tolerates misspellings — "snapcastr mage" finds Snapcaster
 * Mage — where a substring match finds nothing at all.
 *
 * Scryfall answers 404 both when nothing resembles the term and when too many cards do; either way
 * there is no single card to offer.
 */
async function findByFuzzyName(name: string): Promise<CardSearchResult> {
  await new Promise((resolve) => setTimeout(resolve, PAGE_DELAY_MS));

  const url = `${SCRYFALL}/cards/named?fuzzy=${encodeURIComponent(name)}`;

  try {
    const card = await getJson<ScryfallCard>(url, `fuzzy card lookup for '${name}'`);
    return { cards: [toCard(card)], totalMatches: 1 };
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      return { cards: [], totalMatches: 0 };
    }
    throw err;
  }
}

/**
 * Finds every card whose name contains the term.
 *
 * Each whitespace-separated word must appear somewhere in the name, in any order: "bolt light" and
 * "light bolt" both find Lightning Bolt. `name:` matches inside words too, so "olt" finds Aether
 * Revolt. Only the first page comes back; `totalMatches` says how many there were altogether.
 *
 * A term that no name contains falls back to fuzzy matching. Mirrors ScryfallClient.
 */
export async function searchCards(name: string): Promise<CardSearchResult> {
  const query = buildNameQuery(name);
  if (query === null) {
    return { cards: [], totalMatches: 0 };
  }

  const url = `${SCRYFALL}/cards/search?q=${encodeURIComponent(query)}&unique=cards&order=name&dir=asc`;

  let result: ScryfallList;
  try {
    result = await getJson<ScryfallList>(url, `card search for '${name}'`);
  } catch (err) {
    // A 404 means no card name contains the term, which is an empty page, not a failure.
    if (err instanceof ApiError && err.status === 404) {
      return findByFuzzyName(name);
    }
    throw err;
  }

  const matches = result.data ?? [];
  if (matches.length === 0) {
    return findByFuzzyName(name);
  }

  const term = name.trim();
  const cards = matches
    .map(toCard)
    .sort(
      (left, right) =>
        matchRank(left.name, term) - matchRank(right.name, term) ||
        left.name.localeCompare(right.name),
    );

  return { cards, totalMatches: result.total_cards ?? cards.length };
}

/**
 * A single random card, optionally constrained by color.
 *
 * With no colors selected the whole card pool is fair game. Otherwise the selection becomes a
 * Scryfall color query: "only" means no colors outside the selection (`c<=`, so subsets and
 * colorless qualify), "contains" means every selected color is present (`c>=`).
 */
export async function fetchRandomCard(
  colors: string[],
  mode: ColorMatchMode,
): Promise<Card> {
  let url = `${SCRYFALL}/cards/random`;

  if (colors.length > 0) {
    const operator = mode === "only" ? "<=" : ">=";
    const query = `color${operator}${colors.join("").toLowerCase()}`;
    url += `?q=${encodeURIComponent(query)}`;
  }

  const card = await getJson<ScryfallCard>(url, "random card lookup");
  return toCard(card);
}

export async function fetchArtVersions(name: string): Promise<ArtVersion[]> {
  const query = encodeURIComponent(`!"${name}"`);
  let url: string | null = `${SCRYFALL}/cards/search?q=${query}&unique=art&order=released&dir=asc`;

  const versions: ArtVersion[] = [];
  let page = 0;

  while (url !== null && page < MAX_ART_PAGES) {
    if (page > 0) {
      await new Promise((resolve) => setTimeout(resolve, PAGE_DELAY_MS));
    }
    page++;

    let result: ScryfallList;
    try {
      result = await getJson<ScryfallList>(url, `art lookup for '${name}'`);
    } catch (err) {
      // A 404 means the exact name matched no printings, which is an empty result rather than
      // an error worth surfacing.
      if (err instanceof ApiError && err.status === 404) {
        break;
      }
      throw err;
    }

    if (result.data === undefined) {
      break;
    }

    for (const card of result.data) {
      const version = toArtVersion(card);
      if (version !== null) {
        versions.push(version);
      }
    }

    url = result.has_more === true ? (result.next_page ?? null) : null;
  }

  return versions;
}

/**
 * Only TCGplayer is available without the API: its price rides along on the Scryfall card object.
 */
export function fetchVendors(): Promise<VendorInfo[]> {
  return Promise.resolve([
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
