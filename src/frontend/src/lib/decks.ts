/*
 * Deck model, deliberately retained while nothing imports it.
 *
 * The deck-building UI was removed — this app is a card lookup, not a deck builder — but the
 * colour definitions and the add/remove/count logic are kept for planned work around card colours.
 * Delete this file if that never materialises; it is dead code until then.
 */

import type { Card, VendorPrice } from "@/lib/types";

/**
 * A deck the user can build into.
 *
 * Decks are data, not code paths: adding a third deck is one more entry in {@link DECKS}, and
 * every tab, button and counter in the UI picks it up automatically.
 */
export interface DeckDefinition {
  id: string;
  name: string;
  /**
   * Tailwind classes for this deck's accent colour. Written out in full rather than composed
   * from a colour name, because Tailwind only emits classes it can find as literal strings.
   */
  buttonClass: string;
  tabClass: string;
}

export const DECKS: readonly DeckDefinition[] = [
  {
    id: "red",
    name: "Red",
    buttonClass:
      "bg-red-deck/20 border-red-deck text-red-deck hover:bg-red-deck/30",
    tabClass: "bg-red-deck/20 text-red-deck border-b-2 border-red-deck",
  },
  {
    id: "blue",
    name: "Blue",
    buttonClass:
      "bg-blue-deck/20 border-blue-deck text-blue-deck hover:bg-blue-deck/30",
    tabClass: "bg-blue-deck/20 text-blue-deck border-b-2 border-blue-deck",
  },
];

/** One card in a deck, at the art the user picked, with how many copies are in the list. */
export interface DeckEntry {
  card: Card;
  /** The chosen printing's image, which may differ from the card's default. */
  imageUrl: string | null;
  /**
   * Prices for the printing the user actually chose. Held separately from `card.prices`, which
   * belongs to whichever printing the search happened to return — picking the Alpha art of a card
   * should value the deck at Alpha prices, not at the default printing's.
   */
  prices: Record<string, VendorPrice>;
  count: number;
}

export type DeckState = Record<string, DeckEntry[]>;

export function createEmptyDecks(): DeckState {
  return Object.fromEntries(DECKS.map((deck) => [deck.id, []]));
}

/** Deck entries for a deck id. Empty for an unknown id rather than undefined. */
export function entriesFor(decks: DeckState, deckId: string): DeckEntry[] {
  return decks[deckId] ?? [];
}

/** Total copies in a deck, counting duplicates. */
export function countCards(entries: DeckEntry[]): number {
  return entries.reduce((total, entry) => total + entry.count, 0);
}

/**
 * Identity of a deck entry. Card name alone is not enough — the same card added at two different
 * arts is two entries, so the user can keep both printings side by side.
 */
export function entryKey(entry: DeckEntry): string {
  return `${entry.card.name}::${entry.imageUrl ?? ""}`;
}

/** Adds a copy, merging into the matching entry when that card is already in the deck at that art. */
export function addCard(
  entries: DeckEntry[],
  card: Card,
  imageUrl: string | null,
  prices: Record<string, VendorPrice>,
): DeckEntry[] {
  const added: DeckEntry = { card, imageUrl, prices, count: 1 };
  const key = entryKey(added);

  if (entries.some((entry) => entryKey(entry) === key)) {
    return entries.map((entry) =>
      entryKey(entry) === key ? { ...entry, count: entry.count + 1 } : entry,
    );
  }

  return [...entries, added];
}

/** Removes one copy, dropping the entry entirely when the last copy goes. */
export function removeCard(entries: DeckEntry[], target: DeckEntry): DeckEntry[] {
  const key = entryKey(target);

  return entries.flatMap((entry) => {
    if (entryKey(entry) !== key) {
      return [entry];
    }
    return entry.count > 1 ? [{ ...entry, count: entry.count - 1 }] : [];
  });
}
