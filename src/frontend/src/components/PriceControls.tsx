"use client";

import { FINISHES, freshnessLabel, vendorLabel, type Finish } from "@/lib/pricing";
import type { VendorInfo } from "@/lib/types";

interface PriceControlsProps {
  vendors: VendorInfo[];
  vendorId: string;
  finish: Finish;
  onVendorChange: (vendorId: string) => void;
  onFinishChange: (finish: Finish) => void;
  /** "compact" is the inline variant used in the Art Versions header. */
  size: "normal" | "compact";
}

/**
 * Vendor and finish pickers. Rendered twice — once at the top of the page and once in the Art
 * Versions header — with the selection held by the page, so both copies always agree.
 */
export function PriceControls({
  vendors,
  vendorId,
  finish,
  onVendorChange,
  onFinishChange,
  size,
}: PriceControlsProps) {
  const compact = size === "compact";
  const selectClass = `bg-background border border-orange/30 rounded text-foreground focus:outline-none focus:border-orange transition-colors cursor-pointer ${
    compact ? "px-2 py-1 text-xs" : "px-3 py-2 text-sm"
  }`;
  const labelClass = `text-orange font-semibold uppercase tracking-wide ${
    compact ? "text-[10px]" : "text-xs"
  }`;

  const selected = vendors.find((vendor) => vendor.id === vendorId);
  const stillLoading = selected !== undefined && !selected.loaded;

  return (
    <div className={`flex items-center ${compact ? "gap-3" : "gap-5"}`}>
      <div className="flex items-center gap-2">
        <label className={labelClass} htmlFor={`vendor-${size}`}>
          Prices
        </label>
        <select
          id={`vendor-${size}`}
          value={vendorId}
          onChange={(event) => onVendorChange(event.target.value)}
          className={selectClass}
          disabled={vendors.length === 0}
        >
          {vendors.map((vendor) => (
            <option key={vendor.id} value={vendor.id}>
              {vendorLabel(vendor)}
            </option>
          ))}
        </select>
      </div>

      <div className="flex items-center gap-2">
        <label className={labelClass} htmlFor={`finish-${size}`}>
          Finish
        </label>
        <select
          id={`finish-${size}`}
          value={finish}
          onChange={(event) => onFinishChange(event.target.value as Finish)}
          className={selectClass}
        >
          {FINISHES.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {stillLoading ? (
        <span
          className={`flex items-center gap-2 text-foreground/50 ${compact ? "text-[10px]" : "text-xs"}`}
        >
          <span className="inline-block h-3 w-3 border-2 border-foreground/40 border-t-transparent rounded-full animate-spin" />
          Loading prices…
        </span>
      ) : (
        selected !== undefined && (
          <span
            className={`${compact ? "text-[10px]" : "text-xs"} ${
              selected.live ? "text-purple-light" : "text-foreground/40"
            }`}
            title={
              selected.live
                ? "Fetched fresh on every search."
                : "This vendor publishes no per-card endpoint, so prices come from a periodically downloaded catalogue."
            }
          >
            {selected.live ? "● " : ""}
            {freshnessLabel(selected)}
          </span>
        )
      )}
    </div>
  );
}
