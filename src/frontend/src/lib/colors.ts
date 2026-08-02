/** The five Magic colors, in the canonical WUBRG order Scryfall expects in color queries. */
export const COLORS: readonly { id: string; label: string }[] = [
  { id: "W", label: "White" },
  { id: "U", label: "Blue" },
  { id: "B", label: "Black" },
  { id: "R", label: "Red" },
  { id: "G", label: "Green" },
];

/**
 * How the selected colors constrain a random card.
 *
 * "only" allows no colors outside the selection (a subset qualifies, so picking White and Blue can
 * return a mono-white card). "contains" requires every selected color to be present, with any
 * others allowed on top.
 */
export type ColorMatchMode = "only" | "contains";

export const COLOR_MATCH_MODES: readonly { id: ColorMatchMode; label: string }[] = [
  { id: "contains", label: "Contains selected colors" },
  { id: "only", label: "Only selected colors" },
];
