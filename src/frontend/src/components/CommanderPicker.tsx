"use client";

import { useEffect, useRef, useState } from "react";
import { SearchForm } from "@/components/SearchForm";
import { SearchResults } from "@/components/SearchResults";
import { fetchArtVersions, searchCards } from "@/lib/api";
import type { CommanderCard, Player } from "@/lib/game";
import type { Card, CardSearchResult } from "@/lib/types";

interface CommanderPickerProps {
  player: Player;
  /** Called with the picked commander, or null to clear it. The caller closes the overlay. */
  onPick: (commander: CommanderCard | null) => void;
  onClose: () => void;
}

/**
 * Full-screen commander search for one player, reusing the lookup page's form and match grid.
 * Picking a card also fetches its art versions so the zone gets an art crop background — the
 * crop is decoration, so a failed fetch still picks the commander, just without art.
 *
 * Deliberately no legendary filter: partners, backgrounds and planeswalker commanders make
 * "what can be a commander" a moving target, and a wrong guess would block a legal pick.
 */
export function CommanderPicker({ player, onPick, onClose }: CommanderPickerProps) {
  const [query, setQuery] = useState(player.commander?.name ?? "");
  const [results, setResults] = useState<CardSearchResult | null>(null);
  const [searchedTerm, setSearchedTerm] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [picking, setPicking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Same guard as the lookup page: a slow earlier response must not overwrite a later one.
  const requestIdRef = useRef(0);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  async function handleSearch() {
    const name = query.trim();
    if (name === "") {
      return;
    }

    const requestId = ++requestIdRef.current;
    const isCurrent = () => requestId === requestIdRef.current;

    setLoading(true);
    setError(null);
    setResults(null);
    setSearchedTerm(null);

    let found: CardSearchResult;
    try {
      found = await searchCards(name);
    } catch (err) {
      if (isCurrent()) {
        setError(err instanceof Error ? err.message : "Something went wrong.");
        setLoading(false);
      }
      return;
    }

    if (!isCurrent()) {
      return;
    }
    setResults(found);
    setSearchedTerm(name);
    setLoading(false);

    // A single match is unambiguous — pick it straight away, like the lookup page opens it.
    const only = found.cards.length === 1 ? found.cards[0] : undefined;
    if (only !== undefined) {
      void handleSelect(only);
    }
  }

  async function handleSelect(card: Card) {
    const requestId = ++requestIdRef.current;
    setPicking(true);

    let artCropUrl: string | null = null;
    try {
      const versions = await fetchArtVersions(card.name);
      const version = versions.find((entry) => entry.id === card.id) ?? versions[0];
      artCropUrl = version?.artCropUrl ?? null;
    } catch {
      // Decoration only; the pick goes through without a crop.
    }

    if (requestId !== requestIdRef.current) {
      return;
    }
    setPicking(false);
    onPick({ id: card.id, name: card.name, imageUrl: card.imageUrl, artCropUrl });
  }

  return (
    <div className="fixed inset-0 z-40 overflow-y-auto bg-background p-4 sm:p-6">
      <div className="mx-auto max-w-3xl">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-orange font-semibold text-sm uppercase tracking-wide">
            Commander for {player.name}
          </h2>
          <div className="flex gap-2">
            {player.commander !== null && (
              <button
                type="button"
                onClick={() => onPick(null)}
                className="rounded border border-red-deck/50 bg-surface px-3 py-1.5 text-sm font-semibold text-red-deck hover:border-red-deck transition-colors cursor-pointer"
              >
                Remove commander
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              className="rounded border border-purple/40 bg-surface px-3 py-1.5 text-sm font-semibold text-foreground hover:border-purple transition-colors cursor-pointer"
            >
              Close
            </button>
          </div>
        </div>

        <SearchForm
          value={query}
          loading={loading || picking}
          onChange={setQuery}
          onSubmit={() => void handleSearch()}
        />

        {error !== null && (
          <div className="mb-6 rounded-lg border border-red-deck/50 bg-red-deck/20 p-4 text-red-deck">
            {error}
          </div>
        )}

        {searchedTerm !== null && results?.cards.length === 0 && (
          <div className="mb-6 rounded-lg border border-purple/30 bg-surface p-4 text-foreground/60">
            No card name contains &ldquo;{searchedTerm}&rdquo;. Check the spelling, or try a
            shorter piece of the name.
          </div>
        )}

        {results !== null && results.cards.length > 1 && (
          <SearchResults
            cards={results.cards}
            totalMatches={results.totalMatches}
            vendorId=""
            finish="all"
            showPrices={false}
            onSelect={(chosen) => void handleSelect(chosen)}
          />
        )}
      </div>
    </div>
  );
}
