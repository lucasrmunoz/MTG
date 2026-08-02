"use client";

import Image from "next/image";
import { formatPrice, priceFor, type Finish } from "@/lib/pricing";
import type { Card } from "@/lib/types";

interface SearchResultsProps {
  cards: Card[];
  /** How many cards matched overall; larger than cards.length when Scryfall had further pages. */
  totalMatches: number;
  vendorId: string;
  finish: Finish;
  onSelect: (card: Card) => void;
}

/**
 * The match grid for a partial-name search: one image per distinct card, not per printing.
 *
 * Picking a card replaces this grid with the card's detail and art versions; the page brings the
 * grid back through its "back to matches" button. Prices are for the printing Scryfall returns as
 * the card's default. The grid scrolls inside a fixed height so a broad search stays manageable —
 * images load lazily, so off-screen matches cost nothing until scrolled to.
 */
export function SearchResults({
  cards,
  totalMatches,
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

      <div className="max-h-[36rem] overflow-y-auto">
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
          {cards.map((card) => (
            <button
              key={card.id}
              type="button"
              onClick={() => onSelect(card)}
              className="rounded-lg border-2 border-foreground/10 p-2 text-left transition-all cursor-pointer hover:border-orange hover:bg-background/60"
            >
              {card.imageUrl === null ? (
                <div className="aspect-[488/680] w-full bg-background/50 rounded flex items-center justify-center p-2">
                  <span className="text-foreground/60 text-sm text-center">{card.name}</span>
                </div>
              ) : (
                <Image
                  src={card.imageUrl}
                  alt={card.name}
                  width={244}
                  height={340}
                  className="rounded w-full h-auto"
                />
              )}
              <p className="text-sm text-orange font-semibold mt-1">
                {formatPrice(priceFor(card.prices, vendorId, finish))}
              </p>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
