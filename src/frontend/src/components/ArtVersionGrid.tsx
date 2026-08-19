"use client";

import Image from "next/image";
import { useState } from "react";
import { PriceControls } from "@/components/PriceControls";
import { formatPrice, priceFor, type Finish } from "@/lib/pricing";
import type { ArtVersion, VendorInfo } from "@/lib/types";

interface ArtVersionGridProps {
  cardName: string;
  /** Already filtered to the current finish by the page. */
  versions: ArtVersion[];
  /** How many printings exist before the finish filter, for the "N of M" count. */
  totalCount: number;
  loading: boolean;
  selectedUrl: string | null;
  onSelect: (imageUrl: string) => void;
  onHover: (imageUrl: string | null) => void;
  vendors: VendorInfo[];
  vendorId: string;
  finish: Finish;
  onVendorChange: (vendorId: string) => void;
  onFinishChange: (finish: Finish) => void;
}

export function ArtVersionGrid({
  cardName,
  versions,
  totalCount,
  loading,
  selectedUrl,
  onSelect,
  onHover,
  vendors,
  vendorId,
  finish,
  onVendorChange,
  onFinishChange,
}: ArtVersionGridProps) {
  const filtered = versions.length !== totalCount;
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className="mt-6 pt-6 border-t border-purple/20">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <h3 className="section-title">
          Art Versions ({filtered ? `${versions.length} of ${totalCount}` : versions.length})
        </h3>
        <div className="flex flex-wrap items-center gap-3">
          {!collapsed && (
            <PriceControls
              vendors={vendors}
              vendorId={vendorId}
              finish={finish}
              onVendorChange={onVendorChange}
              onFinishChange={onFinishChange}
              size="compact"
            />
          )}
          <button
            type="button"
            onClick={() => setCollapsed((current) => !current)}
            aria-expanded={!collapsed}
            className="btn btn-ghost"
          >
            {collapsed ? "Show" : "Hide"}
          </button>
        </div>
      </div>

      {collapsed ? null : loading ? (
        <div className="flex items-center gap-2 text-foreground/60">
          <span className="spinner text-foreground/40" />
          Loading art versions...
        </div>
      ) : versions.length === 0 ? (
        <p className="text-foreground/40 text-sm py-2">
          No printings of {cardName} exist in this finish.
        </p>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
          {versions.map((art) => (
            <button
              key={art.id}
              type="button"
              onClick={() => onSelect(art.imageUrl)}
              onMouseEnter={() => onHover(art.imageUrl)}
              onMouseLeave={() => onHover(null)}
              onFocus={() => onHover(art.imageUrl)}
              onBlur={() => onHover(null)}
              aria-pressed={selectedUrl === art.imageUrl}
              className={`tile p-2 ${selectedUrl === art.imageUrl ? "tile-selected" : ""}`}
            >
              <Image
                src={art.artCropUrl ?? art.imageUrl}
                alt={`${cardName} — ${art.setName}`}
                width={150}
                height={100}
                className="rounded w-full h-auto"
              />
              <p className="text-xs text-purple-light mt-1 font-medium truncate">
                {art.setName}
              </p>
              <p className="text-sm text-orange font-semibold">
                {formatPrice(priceFor(art.prices, vendorId, finish))}
              </p>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
