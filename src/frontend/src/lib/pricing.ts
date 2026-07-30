import type { VendorInfo, VendorPrice } from "@/lib/types";

/**
 * Which finish the user is looking at. Doubles as a filter on the art grid and as the selector
 * for which of a vendor's two prices to show.
 */
export type Finish = "all" | "nonfoil" | "foil";

export const FINISHES: readonly { id: Finish; label: string }[] = [
  { id: "all", label: "All" },
  { id: "nonfoil", label: "Non-foil" },
  { id: "foil", label: "Foil" },
];

/**
 * The price to display for one printing.
 *
 * Under "all" the nonfoil price wins, falling back to foil so that foil-only printings — Secret
 * Lair drops, promos — still show something rather than a dash.
 */
export function priceFor(
  prices: Record<string, VendorPrice>,
  vendorId: string,
  finish: Finish,
): number | null {
  const price = prices[vendorId];
  if (price === undefined) {
    return null;
  }

  switch (finish) {
    case "foil":
      return price.foil;
    case "nonfoil":
      return price.nonfoil;
    default:
      return price.nonfoil ?? price.foil;
  }
}

export function formatPrice(value: number | null): string {
  return value === null ? "—" : `$${value.toFixed(2)}`;
}

/** Whether a printing survives the current finish filter. */
export function matchesFinish(finishes: string[], finish: Finish): boolean {
  return finish === "all" || finishes.includes(finish);
}

/**
 * Vendor name with its price basis, e.g. "Card Kingdom (NM)". The basis is always shown because
 * the vendors do not measure the same thing: Card Kingdom and Mana Pool quote the cheapest
 * near-mint copy, while TCGplayer's figure is a market price.
 */
export function vendorLabel(vendor: VendorInfo): string {
  return `${vendor.name} (${vendor.priceBasis})`;
}

/**
 * How current a vendor's prices are, for display next to the picker.
 *
 * Live vendors are queried on every request. Card Kingdom publishes no per-card endpoint — only a
 * whole-catalogue download — so its prices are as of the last time the API pulled that file.
 */
export function freshnessLabel(vendor: VendorInfo): string {
  if (vendor.live) {
    return "live";
  }

  if (!vendor.loaded || vendor.fetchedAt === null) {
    return "loading…";
  }

  const minutes = Math.floor(
    (Date.now() - new Date(vendor.fetchedAt).getTime()) / 60_000,
  );

  if (minutes < 1) {
    return "as of just now";
  }
  if (minutes < 60) {
    return `as of ${minutes}m ago`;
  }
  return `as of ${Math.floor(minutes / 60)}h ago`;
}
