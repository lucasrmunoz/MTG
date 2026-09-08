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
 * Land supertypes and the like, available once the type is Land. Every selected one must match,
 * so Nonbasic + Snow finds the nonbasic snow lands. Ids share one namespace with LAND_TYPES.
 *
 * Each entry's `query` is its Scryfall clause. Mirrors ScryfallFilterQuery.
 */
export const LAND_TRAITS: readonly { id: string; label: string; query: string }[] = [
  { id: "basic", label: "Basic", query: "t:basic" },
  { id: "legendary", label: "Legendary", query: "t:legendary" },
  { id: "nonbasic", label: "Nonbasic", query: "-t:basic" },
  { id: "snow", label: "Snow", query: "t:snow" },
];

/**
 * Land subtypes, from the five basic types through to Town and Planet, alphabetically. A land
 * matches when it has any selected one, so Gate + Town lists every Gate and every Town. Combined
 * with LAND_TRAITS: Basic + Island + Swamp finds the basic Islands and Swamps.
 *
 * Mirrors ScryfallFilterQuery.
 */
export const LAND_TYPES: readonly { id: string; label: string; query: string }[] = [
  { id: "cave", label: "Cave", query: "t:cave" },
  { id: "desert", label: "Desert", query: "t:desert" },
  { id: "forest", label: "Forest", query: "t:forest" },
  { id: "gate", label: "Gate", query: "t:gate" },
  { id: "island", label: "Island", query: "t:island" },
  { id: "lair", label: "Lair", query: "t:lair" },
  { id: "locus", label: "Locus", query: "t:locus" },
  { id: "mountain", label: "Mountain", query: "t:mountain" },
  { id: "plains", label: "Plains", query: "t:plains" },
  { id: "planet", label: "Planet", query: "t:planet" },
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
  /** LAND_TRAITS and LAND_TYPES ids. Only applied when `type` is "land". */
  landTraits: string[];
}

/**
 * The default color mode is "only" rather than the random draw's "contains": with every color
 * selected, "only" admits any color combination, while "contains" would demand all five.
 */
export const NO_FILTERS: SearchFilters = {
  type: null,
  colors: [],
  colorMode: "only",
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

    const types = LAND_TYPES.filter((type) => filters.landTraits.includes(type.id));
    if (types.length > 0) {
      clauses.push(`(${types.map((type) => type.query).join(" or ")})`);
    }
  }

  return clauses;
}
