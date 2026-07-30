/** Mirrors the JSON returned by the Mtg.Api endpoints. */

export interface CardFace {
  name: string;
  manaCost: string;
  typeLine: string;
  oracleText: string;
  power: string | null;
  toughness: string | null;
  imageUrl: string | null;
}

/** What one vendor charges for one printing, in USD. Null means no price to show. */
export interface VendorPrice {
  nonfoil: number | null;
  foil: number | null;
}

/** A price vendor, as advertised by GET /api/vendors. */
export interface VendorInfo {
  id: string;
  name: string;
  /** What the number means, e.g. "NM" or "market". */
  priceBasis: string;
  /** True when prices are fetched fresh per request rather than from a cached catalogue. */
  live: boolean;
  /** For cached vendors, when the catalogue was last downloaded (ISO, UTC). */
  fetchedAt: string | null;
  /** False only while a cached vendor's first download is still running. */
  loaded: boolean;
}

export interface Card {
  /** Scryfall's id for this printing. */
  id: string;
  name: string;
  manaCost: string;
  manaValue: number;
  typeLine: string;
  oracleText: string;
  power: string | null;
  toughness: string | null;
  loyalty: string | null;
  colors: string[];
  colorIdentity: string[];
  keywords: string[];
  rarity: string;
  setCode: string;
  setName: string;
  imageUrl: string | null;
  /** Finishes this printing was produced in: nonfoil, foil and/or etched. */
  finishes: string[];
  /** Keyed by vendor id. Vendors with no price for this printing are absent. */
  prices: Record<string, VendorPrice>;
  faces: CardFace[];
}

export interface ArtVersion {
  id: string;
  setCode: string;
  setName: string;
  collectorNumber: string;
  artist: string;
  releasedAt: string | null;
  imageUrl: string;
  artCropUrl: string | null;
  finishes: string[];
  prices: Record<string, VendorPrice>;
}
