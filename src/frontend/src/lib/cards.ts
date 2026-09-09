import type { Card } from "@/lib/types";

/**
 * The type portion of a type line, dropping the subtypes after the em dash.
 * "Legendary Creature — Human Wizard" becomes "Legendary Creature"; "Instant" stays "Instant".
 */
export function primaryType(typeLine: string): string {
  const [types] = typeLine.split("—");
  return types?.trim() ?? typeLine;
}

/**
 * The back face's image of the card's default printing, or null when the card has one face.
 * Only double-faced cards carry a scan per face; split and adventure cards share one.
 */
export function backImageUrl(card: Card): string | null {
  const faces = card.faces.filter((face) => face.imageUrl !== null);
  return faces[1]?.imageUrl ?? null;
}

/** Power/toughness as printed, or null when the card is not a creature. */
export function powerToughness(card: Card): string | null {
  if (card.power === null || card.toughness === null) {
    return null;
  }
  return `${card.power}/${card.toughness}`;
}
