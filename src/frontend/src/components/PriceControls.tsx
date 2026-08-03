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
  /**
   * Re-downloads the selected vendor's cached catalogue. Only the Android app passes this — on
   * the web the catalogue lives server-side or not at all — and the button only renders for a
   * cached vendor that has finished its first load.
   */
  onRefresh?: (() => void) | undefined;
  /** True while a manual catalogue refresh is downloading. */
  refreshing?: boolean | undefined;
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
  onRefresh,
  refreshing,
}: PriceControlsProps) {
  const compact = size === "compact";
  // min-w-0 lets the select shrink below the width of its longest option; without it the row has a
  // ~415px intrinsic minimum, which forces phones to shrink-to-fit the whole page.
  const selectClass = `min-w-0 flex-1 sm:flex-none bg-background border border-orange/30 rounded text-foreground focus:outline-none focus:border-orange transition-colors cursor-pointer ${
    compact ? "px-2 py-1 text-xs" : "px-3 py-2 text-sm"
  }`;
  const labelClass = `flex-shrink-0 text-orange font-semibold uppercase tracking-wide ${
    compact ? "text-[10px]" : "text-xs"
  }`;
  const groupClass = "flex min-w-0 flex-1 items-center gap-2 sm:flex-none";

  const selected = vendors.find((vendor) => vendor.id === vendorId);
  const stillLoading = selected !== undefined && !selected.loaded;

  return (
    <div
      className={`flex w-full flex-wrap items-center sm:w-auto ${
        compact ? "gap-x-3 gap-y-2" : "gap-x-5 gap-y-2"
      }`}
    >
      <div className={groupClass}>
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

      <div className={groupClass}>
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

      {onRefresh !== undefined && selected !== undefined && !selected.live && selected.loaded && (
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing === true}
          title="Download this vendor's price catalogue again now, replacing the cached copy"
          className={`bg-background border border-orange/30 hover:border-orange disabled:opacity-60 text-foreground rounded transition-colors cursor-pointer ${
            compact ? "px-2 py-1 text-[10px]" : "px-3 py-1.5 text-xs"
          }`}
        >
          {refreshing === true ? "Refreshing…" : "↻ Refresh"}
        </button>
      )}
    </div>
  );
}
