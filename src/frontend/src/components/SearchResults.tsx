"use client";

import { formatPrice, priceFor, type Finish } from "@/lib/pricing";
import type { Card } from "@/lib/types";

interface SearchResultsProps {
  cards: Card[];
  /** How many cards matched overall; larger than cards.length when Scryfall had further pages. */
  totalMatches: number;
  /** The card currently open below, so the list can show which one that is. */
  selectedId: string | null;
  vendorId: string;
  finish: Finish;
  onSelect: (card: Card) => void;
}

/**
 * The pick-list for a partial-name search.
 *
 * Prices here are for the printing Scryfall returns as the card's default. Picking a card opens it
 * below, where every printing's own price is listed.
 */
export function SearchResults({
  cards,
  totalMatches,
  selectedId,
  vendorId,
  finish,
  onSelect,
}: SearchResultsProps) {
  const truncated = totalMatches > cards.length;

  return (
    <div className="bg-surface rounded-lg border border-purple/30 p-4 sm:p-6 mb-6 sm:mb-8">
      <h2 className="text-orange font-semibold text-sm uppercase tracking-wide mb-1">
        Matches (
        {truncated
          ? `${cards.length} of ${totalMatches.toLocaleString("en-US")}`
          : cards.length}
        )
      </h2>
      <p className="text-foreground/50 text-sm mb-4">
        {truncated
          ? "Showing the first page only — type more of the name to narrow it down."
          : "Pick a card to see its printings and prices."}
      </p>

      <ul className="max-h-96 overflow-y-auto divide-y divide-foreground/10">
        {cards.map((card) => (
          <li key={card.id}>
            <button
              type="button"
              onClick={() => onSelect(card)}
              className={`w-full flex items-baseline justify-between gap-4 px-3 py-2 text-left transition-colors cursor-pointer ${
                selectedId === card.id ? "bg-orange/10" : "hover:bg-background/60"
              }`}
            >
              {/* min-w-0 lets the long type line truncate instead of pushing the price off. */}
              <span className="min-w-0">
                <span className="block truncate font-medium text-purple-light">
                  {card.name}
                </span>
                <span className="block truncate text-sm text-foreground/50">
                  {card.typeLine} · {card.setName} ({card.setCode.toUpperCase()})
                </span>
              </span>
              <span className="flex-shrink-0 text-orange font-semibold">
                {formatPrice(priceFor(card.prices, vendorId, finish))}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
