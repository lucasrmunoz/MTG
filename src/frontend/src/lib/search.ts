import type { ColorMatchMode } from "@/lib/colors";

/**
 * Card types a search can be narrowed to. Ids are the words Scryfall's `t:` operator takes, and
 * are what Mtg.Api accepts in its `type` parameter. Mirrors ScryfallFilterQuery.
 */
export const CARD_TYPES: readonly { id: string; label: string }[] = [
  { id: "artifact", label: "Artifact" },
  { id: "battle", label: "Battle" },
  { id: "creature", label: "Creature" },
  { id: "enchantment", label: "Enchantment" },
  { id: "instant", label: "Instant" },
  { id: "kindred", label: "Kindred" },
  { id: "land", label: "Land" },
  { id: "planeswalker", label: "Planeswalker" },
  { id: "sorcery", label: "Sorcery" },
];

/**
 * Extra narrowing available once the type is Land: supertypes like Basic and Snow, and land
 * subtypes from the five basic types through to Town and Planet, listed alphabetically. Every
 * selected trait must match, so Basic + Island finds the basic Islands and Island + Swamp finds
 * the Island Swamp duals.
 *
 * Each trait's `query` is its Scryfall clause. Mirrors ScryfallFilterQuery.
 */
export const LAND_TRAITS: readonly { id: string; label: string; query: string }[] = [
  { id: "basic", label: "Basic", query: "t:basic" },
  { id: "cave", label: "Cave", query: "t:cave" },
  { id: "desert", label: "Desert", query: "t:desert" },
  { id: "forest", label: "Forest", query: "t:forest" },
  { id: "gate", label: "Gate", query: "t:gate" },
  { id: "island", label: "Island", query: "t:island" },
  { id: "lair", label: "Lair", query: "t:lair" },
  { id: "legendary", label: "Legendary", query: "t:legendary" },
  { id: "locus", label: "Locus", query: "t:locus" },
  { id: "mountain", label: "Mountain", query: "t:mountain" },
  { id: "nonbasic", label: "Nonbasic", query: "-t:basic" },
  { id: "plains", label: "Plains", query: "t:plains" },
  { id: "planet", label: "Planet", query: "t:planet" },
  { id: "snow", label: "Snow", query: "t:snow" },
  { id: "sphere", label: "Sphere", query: "t:sphere" },
  { id: "swamp", label: "Swamp", query: "t:swamp" },
  { id: "town", label: "Town", query: "t:town" },
];

/** Constraints a search applies alongside the name term. */
export interface SearchFilters {
  /** A CARD_TYPES id, or null for any type. */
  type: string | null;
  /** COLORS ids. Empty means any color. */
  colors: string[];
  colorMode: ColorMatchMode;
  /** LAND_TRAITS ids. Only applied when `type` is "land". */
  landTraits: string[];
}

export const NO_FILTERS: SearchFilters = {
  type: null,
  colors: [],
  colorMode: "contains",
  landTraits: [],
};

/** Whether the filters narrow anything — a search with an empty name still makes sense then. */
export function hasFilters(filters: SearchFilters): boolean {
  return filters.type !== null || filters.colors.length > 0;
}

/**
 * The Scryfall clauses for a set of filters, to be ANDed onto the name clauses.
 *
 * Colors use the same `color>=` / `color<=` forms as the random card draw — except for lands,
 * which are colorless as printed: there the filter goes by color identity (`id`) instead, so
 * "blue lands" finds Island and Breeding Pool rather than nothing. Mirrors ScryfallFilterQuery.
 */
export function buildFilterQuery(filters: SearchFilters): string[] {
  const clauses: string[] = [];

  if (filters.type !== null) {
    clauses.push(`t:${filters.type}`);
  }

  if (filters.colors.length > 0) {
    const field = filters.type === "land" ? "id" : "color";
    const operator = filters.colorMode === "only" ? "<=" : ">=";
    clauses.push(`${field}${operator}${filters.colors.join("").toLowerCase()}`);
  }

  if (filters.type === "land") {
    for (const trait of LAND_TRAITS) {
      if (filters.landTraits.includes(trait.id)) {
        clauses.push(trait.query);
      }
    }
  }

  return clauses;
}
