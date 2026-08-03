/**
 * Live prices from Mana Pool's public API, called from inside the Android app.
 *
 * Mana Pool sends no CORS headers, so browser JavaScript can never call it — on the web that is
 * Mtg.Api's job. Inside Capacitor the request runs on the native side via CapacitorHttp, where
 * CORS does not exist. Mirrors Mtg.Core's ManaPoolLiveSource. Keep the two in step.
 */

import { CapacitorHttp } from "@capacitor/core";
import { ApiError } from "@/lib/errors";
import type { VendorPrice } from "@/lib/types";

const ENDPOINT = "https://manapool.com/api/v1/products/singles";

/** Mana Pool caps scryfall_ids at 100 per call, so larger sets are chunked. */
const MAX_IDS_PER_REQUEST = 100;

/**
 * Mana Pool validates each id against a UUID pattern restricted to versions 1-8. A single id
 * outside it fails the whole request with a 400, so non-conforming ids are dropped rather than
 * allowed to cost every other printing in the batch.
 */
const ACCEPTED_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

interface ManaPoolRow {
  scryfall_id?: string;
  price_cents_nm?: number | null;
  price_cents_nm_foil?: number | null;
}

interface ManaPoolResponse {
  data?: ManaPoolRow[];
}

/** Mana Pool quotes cents; zero and negative values mean "no price", not "free". */
function toDollars(cents: number | null | undefined): number | null {
  return typeof cents === "number" && cents > 0 ? cents / 100 : null;
}

/** Live near-mint prices for the given printings, keyed by lowercased Scryfall id. */
export async function fetchPrices(
  printingIds: readonly string[],
): Promise<Map<string, VendorPrice>> {
  const prices = new Map<string, VendorPrice>();
  const accepted = [...new Set(printingIds)].filter((id) => ACCEPTED_ID.test(id));

  for (let start = 0; start < accepted.length; start += MAX_IDS_PER_REQUEST) {
    const chunk = accepted.slice(start, start + MAX_IDS_PER_REQUEST);

    // scryfall_ids is an array parameter and must be repeated per value. Comma-joining them is
    // rejected with a 400, which would cost the whole batch.
    const query = chunk.map((id) => `scryfall_ids=${id}`).join("&");

    let response;
    try {
      response = await CapacitorHttp.get({
        url: `${ENDPOINT}?${query}`,
        headers: { Accept: "application/json" },
      });
    } catch {
      throw new ApiError("Could not reach Mana Pool for live prices.", 0);
    }

    if (response.status < 200 || response.status >= 300) {
      throw new ApiError(
        `Mana Pool returned ${response.status} during a price lookup.`,
        response.status,
      );
    }

    // CapacitorHttp parses JSON responses itself but hands back a string when the content type
    // claims otherwise.
    const body =
      typeof response.data === "string"
        ? (JSON.parse(response.data) as ManaPoolResponse)
        : (response.data as ManaPoolResponse);

    for (const row of body.data ?? []) {
      const price: VendorPrice = {
        nonfoil: toDollars(row.price_cents_nm),
        foil: toDollars(row.price_cents_nm_foil),
      };

      if (
        row.scryfall_id !== undefined &&
        row.scryfall_id !== "" &&
        (price.nonfoil !== null || price.foil !== null)
      ) {
        prices.set(row.scryfall_id.toLowerCase(), price);
      }
    }
  }

  return prices;
}
