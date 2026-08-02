"use client";

import { useEffect, useRef, useState } from "react";
import { ArtVersionGrid } from "@/components/ArtVersionGrid";
import { CardDetail } from "@/components/CardDetail";
import { KeywordsSection } from "@/components/KeywordsSection";
import { PreviewPanel } from "@/components/PreviewPanel";
import { PriceControls } from "@/components/PriceControls";
import { RandomCardControls } from "@/components/RandomCardControls";
import { SearchForm } from "@/components/SearchForm";
import { SearchResults } from "@/components/SearchResults";
import { fetchArtVersions, fetchRandomCard, fetchVendors, searchCards } from "@/lib/api";
import type { ColorMatchMode } from "@/lib/colors";
import { matchesFinish, priceFor, type Finish } from "@/lib/pricing";
import type { ArtVersion, Card, CardSearchResult, VendorInfo } from "@/lib/types";

/** How often to re-check whether the cached vendor catalogue has finished downloading. */
const VENDOR_POLL_MS = 10_000;

export default function Home() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<CardSearchResult | null>(null);
  const [card, setCard] = useState<Card | null>(null);
  const [loading, setLoading] = useState(false);
  const [randomLoading, setRandomLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** The term the current results belong to, so the empty state can quote it back. */
  const [searchedTerm, setSearchedTerm] = useState<string | null>(null);

  const [artVersions, setArtVersions] = useState<ArtVersion[]>([]);
  const [loadingArt, setLoadingArt] = useState(false);
  const [selectedArtUrl, setSelectedArtUrl] = useState<string | null>(null);
  const [hoveredArtUrl, setHoveredArtUrl] = useState<string | null>(null);

  const [vendors, setVendors] = useState<VendorInfo[]>([]);
  const [vendorId, setVendorId] = useState("");
  const [finish, setFinish] = useState<Finish>("all");

  // Identifies the in-flight search or card selection, so a slow response from an earlier one
  // cannot overwrite the results of a later one.
  const requestIdRef = useRef(0);

  // Card Kingdom publishes no per-card endpoint, so its catalogue is downloaded in the background
  // and its prices appear shortly after startup. Poll until it reports loaded, then stop.
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function loadVendors() {
      let list: VendorInfo[];
      try {
        list = await fetchVendors();
      } catch {
        // The page is still usable without vendor metadata; prices simply render as dashes.
        return;
      }

      if (cancelled) {
        return;
      }

      setVendors(list);
      setVendorId((current) => (current === "" ? (list[0]?.id ?? "") : current));

      if (list.some((vendor) => !vendor.loaded)) {
        timer = setTimeout(() => void loadVendors(), VENDOR_POLL_MS);
      }
    }

    void loadVendors();

    return () => {
      cancelled = true;
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    };
  }, []);

  /** Opens one card from the match list and loads its printings. */
  async function selectCard(chosen: Card) {
    const requestId = ++requestIdRef.current;
    const isCurrent = () => requestId === requestIdRef.current;

    setCard(chosen);
    setSelectedArtUrl(chosen.imageUrl);
    setHoveredArtUrl(null);
    setArtVersions([]);
    setLoadingArt(true);

    try {
      const versions = await fetchArtVersions(chosen.name);
      if (isCurrent()) {
        setArtVersions(versions);
      }
    } catch {
      // Art versions are supplementary; losing them should not blank out the card.
      if (isCurrent()) {
        setArtVersions([]);
      }
    } finally {
      if (isCurrent()) {
        setLoadingArt(false);
      }
    }
  }

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
    setCard(null);
    setArtVersions([]);
    // A card selection may still be loading art, or a random card may still be drawing; those
    // responses are discarded by the id check, so the flags they would have cleared reset here.
    setLoadingArt(false);
    setRandomLoading(false);
    setSelectedArtUrl(null);
    setHoveredArtUrl(null);

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

    // A single match is unambiguous, so skip the pick-list and open it straight away.
    const only = found.cards.length === 1 ? found.cards[0] : undefined;
    if (only !== undefined) {
      void selectCard(only);
    }
  }

  /** Draws one random card, optionally color-filtered, and opens it like a picked search result. */
  async function handleRandomCard(colors: string[], mode: ColorMatchMode) {
    const requestId = ++requestIdRef.current;
    const isCurrent = () => requestId === requestIdRef.current;

    setRandomLoading(true);
    setError(null);
    setResults(null);
    setSearchedTerm(null);
    setCard(null);
    setArtVersions([]);
    // Mirrors handleSearch: an interrupted search or art load never clears its own flag, because
    // the id check discards its response.
    setLoading(false);
    setLoadingArt(false);
    setSelectedArtUrl(null);
    setHoveredArtUrl(null);

    let drawn: Card;
    try {
      drawn = await fetchRandomCard(colors, mode);
    } catch (err) {
      if (isCurrent()) {
        setError(err instanceof Error ? err.message : "Something went wrong.");
        setRandomLoading(false);
      }
      return;
    }

    if (!isCurrent()) {
      return;
    }

    setRandomLoading(false);
    void selectCard(drawn);
  }

  const visibleVersions = artVersions.filter((version) =>
    matchesFinish(version.finishes, finish),
  );
  const previewUrl = hoveredArtUrl ?? selectedArtUrl;
  const previewVersion = artVersions.find((version) => version.imageUrl === previewUrl);
  const previewPrice =
    previewVersion !== undefined
      ? priceFor(previewVersion.prices, vendorId, finish)
      : card !== null
        ? priceFor(card.prices, vendorId, finish)
        : null;
  const previewPrinting =
    previewVersion !== undefined
      ? `${previewVersion.setName} (${previewVersion.setCode.toUpperCase()}) #${previewVersion.collectorNumber}`
      : card !== null
        ? `${card.setName} (${card.setCode.toUpperCase()})`
        : null;

  return (
    <div className="min-h-screen bg-background p-4 sm:p-6 lg:p-8">
      <div className="mx-auto max-w-7xl">
        <div className="flex flex-wrap items-start justify-between gap-4 mb-6 sm:mb-8">
          <div className="min-w-0">
            <h1 className="text-2xl sm:text-3xl lg:text-4xl font-bold text-orange mb-1">
              MTG Card Lookup
            </h1>
            <p className="text-purple-light text-base sm:text-lg">
              Search Magic: The Gathering cards, browse every printing, and compare prices.
            </p>
          </div>
          <PriceControls
            vendors={vendors}
            vendorId={vendorId}
            finish={finish}
            onVendorChange={setVendorId}
            onFinishChange={setFinish}
            size="normal"
          />
        </div>

        <div className="flex flex-col lg:flex-row gap-8">
          <div className="flex-1 min-w-0">
            <SearchForm
              value={query}
              loading={loading}
              onChange={setQuery}
              onSubmit={handleSearch}
            />

            <RandomCardControls
              loading={randomLoading}
              onRandom={(colors, mode) => void handleRandomCard(colors, mode)}
            />

            {error !== null && (
              <div className="bg-red-deck/20 border border-red-deck/50 text-red-deck rounded-lg p-4 mb-8">
                {error}
              </div>
            )}

            {searchedTerm !== null && results?.cards.length === 0 && (
              <div className="bg-surface rounded-lg border border-purple/30 p-4 sm:p-6 mb-6 sm:mb-8 text-foreground/60">
                No card name contains &ldquo;{searchedTerm}&rdquo;. Check the spelling, or try a
                shorter piece of the name.
              </div>
            )}

            {results !== null && results.cards.length > 1 && (
              <SearchResults
                cards={results.cards}
                totalMatches={results.totalMatches}
                selectedId={card?.id ?? null}
                vendorId={vendorId}
                finish={finish}
                onSelect={(chosen) => void selectCard(chosen)}
              />
            )}

            {card !== null && (
              <div className="bg-surface rounded-lg border border-purple/30 p-4 sm:p-6">
                <h2 className="text-orange font-semibold text-sm uppercase tracking-wide mb-4">
                  Selected Card
                </h2>

                <CardDetail
                  card={card}
                  imageUrl={selectedArtUrl ?? card.imageUrl}
                  vendors={vendors}
                  vendorId={vendorId}
                  finish={finish}
                />

                {(loadingArt || artVersions.length > 1) && (
                  <ArtVersionGrid
                    cardName={card.name}
                    versions={visibleVersions}
                    totalCount={artVersions.length}
                    loading={loadingArt}
                    selectedUrl={selectedArtUrl}
                    onSelect={setSelectedArtUrl}
                    onHover={setHoveredArtUrl}
                    vendors={vendors}
                    vendorId={vendorId}
                    finish={finish}
                    onVendorChange={setVendorId}
                    onFinishChange={setFinish}
                  />
                )}
              </div>
            )}
          </div>

          <div className="w-full lg:w-80 flex-shrink-0">
            <PreviewPanel
              imageUrl={card === null ? null : previewUrl}
              label={card?.name ?? ""}
              price={previewPrice}
              printing={previewPrinting}
              vendors={vendors}
              vendorId={vendorId}
              finish={finish}
            />
          </div>
        </div>

        <div className="mt-6 sm:mt-8">
          <KeywordsSection />
        </div>
      </div>
    </div>
  );
}
