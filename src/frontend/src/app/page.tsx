"use client";

import { useEffect, useRef, useState } from "react";
import { ArtVersionGrid } from "@/components/ArtVersionGrid";
import { CardDetail } from "@/components/CardDetail";
import { PreviewPanel } from "@/components/PreviewPanel";
import { PriceControls } from "@/components/PriceControls";
import { SearchForm } from "@/components/SearchForm";
import { fetchArtVersions, fetchVendors, searchCard } from "@/lib/api";
import { matchesFinish, priceFor, type Finish } from "@/lib/pricing";
import type { ArtVersion, Card, VendorInfo } from "@/lib/types";

/** How often to re-check whether the cached vendor catalogue has finished downloading. */
const VENDOR_POLL_MS = 10_000;

export default function Home() {
  const [query, setQuery] = useState("");
  const [card, setCard] = useState<Card | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [artVersions, setArtVersions] = useState<ArtVersion[]>([]);
  const [loadingArt, setLoadingArt] = useState(false);
  const [selectedArtUrl, setSelectedArtUrl] = useState<string | null>(null);
  const [hoveredArtUrl, setHoveredArtUrl] = useState<string | null>(null);

  const [vendors, setVendors] = useState<VendorInfo[]>([]);
  const [vendorId, setVendorId] = useState("");
  const [finish, setFinish] = useState<Finish>("all");

  // Identifies the in-flight search so a slow response from an earlier query cannot overwrite
  // the results of a later one.
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

  async function handleSearch() {
    const name = query.trim();
    if (name === "") {
      return;
    }

    const requestId = ++requestIdRef.current;
    const isCurrent = () => requestId === requestIdRef.current;

    setLoading(true);
    setError(null);
    setCard(null);
    setArtVersions([]);
    setSelectedArtUrl(null);
    setHoveredArtUrl(null);

    let found: Card;
    try {
      found = await searchCard(name);
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

    setCard(found);
    setSelectedArtUrl(found.imageUrl);
    setLoading(false);
    setLoadingArt(true);

    try {
      const versions = await fetchArtVersions(found.name);
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

            {error !== null && (
              <div className="bg-red-deck/20 border border-red-deck/50 text-red-deck rounded-lg p-4 mb-8">
                {error}
              </div>
            )}

            {card !== null && (
              <div className="bg-surface rounded-lg border border-purple/30 p-4 sm:p-6">
                <h2 className="text-orange font-semibold text-sm uppercase tracking-wide mb-4">
                  Search Result
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
      </div>
    </div>
  );
}
