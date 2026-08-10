"use client";

import { CardImage } from "@/components/CardImage";
import { formatPrice, vendorLabel, type Finish } from "@/lib/pricing";
import type { VendorInfo } from "@/lib/types";

interface PreviewPanelProps {
  /** Full-size art to show; null renders the empty state. */
  imageUrl: string | null;
  label: string;
  price: number | null;
  /** Set and collector number of the previewed printing, when one is selected. */
  printing: string | null;
  vendors: VendorInfo[];
  vendorId: string;
  finish: Finish;
}

/**
 * The right-hand panel: a large view of whichever printing is selected or hovered, with its price
 * at the current vendor and finish.
 */
export function PreviewPanel({
  imageUrl,
  label,
  price,
  printing,
  vendors,
  vendorId,
  finish,
}: PreviewPanelProps) {
  const vendor = vendors.find((candidate) => candidate.id === vendorId);

  return (
    <div className="panel p-4 sticky top-8">
      <h2 className="section-title mb-4">Preview</h2>

      {imageUrl === null ? (
        <p className="text-foreground/40 text-sm text-center py-8">
          Search for a card to preview its artwork.
        </p>
      ) : (
        <>
          <div className="flex items-baseline justify-between gap-2 mb-2">
            <p className="text-purple-light font-semibold text-sm truncate">{label}</p>
            <p className="text-lg font-bold text-orange flex-shrink-0">
              {formatPrice(price)}
            </p>
          </div>

          {printing !== null && (
            <p className="text-foreground/50 text-xs mb-3 truncate">{printing}</p>
          )}

          <CardImage
            src={imageUrl}
            alt={label}
            width={288}
            height={401}
            foil={finish === "foil"}
            className="rounded-lg w-full h-auto shadow-[0_12px_32px_-12px_rgba(0,0,0,0.7)]"
          />

          {vendor !== undefined && (
            <p className="text-foreground/40 text-xs mt-3 text-center">
              {vendorLabel(vendor)}
              {finish === "foil" ? " · foil" : ""}
            </p>
          )}
        </>
      )}
    </div>
  );
}
