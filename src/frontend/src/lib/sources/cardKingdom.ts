/**
 * Card Kingdom's price catalogue, downloaded and cached on the device by the Android app.
 *
 * Card Kingdom publishes no per-card endpoint — only a ~66 MB whole-catalogue download — which is
 * why on the web its prices exist only in API mode. The app downloads that catalogue itself: on
 * demand the first time the vendor is selected, then from a cached file until that cache goes
 * stale or the user refreshes it by hand.
 *
 * There is exactly one cache file, overwritten in place on every successful download — never a
 * second copy or a history of snapshots. The catalogue is parsed row by row off the network
 * stream instead of one giant JSON.parse, because materialising ~149,000 full row objects at once
 * costs hundreds of megabytes — enough to crash a phone WebView. The feed sends
 * `access-control-allow-origin: *`, so a plain fetch works and the bytes never cross the native
 * bridge. Parsing rules mirror Mtg.Core's CardKingdomFeed. Keep the two in step.
 */

import { Directory, Encoding, Filesystem } from "@capacitor/filesystem";
import { ApiError } from "@/lib/errors";
import type { VendorPrice } from "@/lib/types";

const FEED_URL = "https://api.cardkingdom.com/api/v2/pricelist";

/** The one cache file, in the app's private data directory. */
const CACHE_FILE = "card-kingdom-prices.json";

/** How long a downloaded catalogue counts as fresh. After this, selecting the vendor re-downloads. */
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

/**
 * Standard UUID, any version. Roughly 700 feed rows carry something else — null for Card
 * Kingdom's own tokens, bare numbers, at least one truncated GUID — and are skipped, mirroring
 * the C# feed's Guid.TryParse guard.
 */
const PRINTING_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const EMPTY_ID = "00000000-0000-0000-0000-000000000000";

let catalogue: Map<string, VendorPrice> | null = null;
let fetchedAt: string | null = null;

/** Single-flight guard so a manual refresh cannot race an on-demand load into two downloads. */
let inFlight: Promise<void> | null = null;

/** Shape of the cache file. */
interface CacheFile {
  fetchedAt: string;
  prices: Record<string, VendorPrice>;
}

/** The fields actually read from a feed row; everything else in the row is ignored. */
interface CatalogueRow {
  scryfall_id?: string | null;
  /** Quoted as the string "true" or "false", not a JSON boolean. */
  is_foil?: string;
  condition_values?: { nm_price?: string };
}

/**
 * Extracts complete top-level objects from a JSON document that arrives as text chunks.
 *
 * The feed is `{"meta":{…},"data":[{row},{row},…]}`. Every object that opens directly inside the
 * root — the meta block and each row — is emitted whole; the caller discards anything without a
 * usable scryfall_id, which disposes of the meta block for free. Tracking only brace depth and
 * string state is enough, because the data array's brackets never change what "directly inside
 * the root" means.
 */
class RowScanner {
  private buffer = "";
  private pos = 0;
  private depth = 0;
  private inString = false;
  private escaped = false;
  private objectStart = -1;

  push(chunk: string): string[] {
    this.buffer += chunk;
    const rows: string[] = [];

    while (this.pos < this.buffer.length) {
      const ch = this.buffer.charAt(this.pos);

      if (this.inString) {
        if (this.escaped) {
          this.escaped = false;
        } else if (ch === "\\") {
          this.escaped = true;
        } else if (ch === '"') {
          this.inString = false;
        }
      } else if (ch === '"') {
        this.inString = true;
      } else if (ch === "{") {
        if (this.depth === 1) {
          this.objectStart = this.pos;
        }
        this.depth++;
      } else if (ch === "}") {
        this.depth--;
        if (this.depth === 1 && this.objectStart !== -1) {
          rows.push(this.buffer.slice(this.objectStart, this.pos + 1));
          this.objectStart = -1;
        }
      }

      this.pos++;
    }

    // Drop consumed text so the buffer holds at most one partial row, not the whole download.
    const keepFrom = this.objectStart === -1 ? this.pos : this.objectStart;
    this.buffer = this.buffer.slice(keepFrom);
    this.pos -= keepFrom;
    if (this.objectStart !== -1) {
      this.objectStart -= keepFrom;
    }

    return rows;
  }
}

/**
 * Card Kingdom quotes prices as decimal strings. The C# feed parses them with
 * NumberStyles.Number, which tolerates thousands separators, so commas are stripped here too.
 */
function parsePrice(value: string | undefined): number | null {
  if (value === undefined) {
    return null;
  }
  const parsed = Number(value.replaceAll(",", ""));
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

/**
 * Folds one feed row into the map. Foil and nonfoil arrive as separate rows sharing one
 * scryfall_id, which is why rows merge rather than replace.
 */
function addRow(rowText: string, prices: Map<string, VendorPrice>): void {
  let row: CatalogueRow;
  try {
    row = JSON.parse(rowText) as CatalogueRow;
  } catch {
    return; // Not a well-formed object; skipped just like a row with no usable id.
  }

  const id = row.scryfall_id?.toLowerCase();
  if (id === undefined || !PRINTING_ID.test(id) || id === EMPTY_ID) {
    return;
  }

  const nearMint = parsePrice(row.condition_values?.nm_price);
  if (nearMint === null) {
    return;
  }

  const existing = prices.get(id);
  prices.set(
    id,
    row.is_foil?.toLowerCase() === "true"
      ? { nonfoil: existing?.nonfoil ?? null, foil: nearMint }
      : { nonfoil: nearMint, foil: existing?.foil ?? null },
  );
}

/** Downloads and parses the catalogue, then replaces both the in-memory map and the cache file. */
async function download(): Promise<void> {
  let response: Response;
  try {
    response = await fetch(FEED_URL, { headers: { Accept: "application/json" } });
  } catch {
    throw new ApiError("Could not reach the Card Kingdom price feed.", 0);
  }

  if (!response.ok) {
    throw new ApiError(
      `The Card Kingdom price feed returned ${response.status}.`,
      response.status,
    );
  }

  if (response.body === null) {
    throw new ApiError("The Card Kingdom price feed returned an empty body.", 0);
  }

  const prices = new Map<string, VendorPrice>();
  const scanner = new RowScanner();
  const decoder = new TextDecoder();
  const reader = response.body.getReader();

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      for (const rowText of scanner.push(decoder.decode(value, { stream: true }))) {
        addRow(rowText, prices);
      }
    }
  } catch {
    // The connection dropped mid-download. Nothing parsed so far is kept: the maps are replaced
    // only after a complete read, so the previous catalogue stays intact.
    throw new ApiError("The Card Kingdom price feed download was interrupted.", 0);
  }
  for (const rowText of scanner.push(decoder.decode())) {
    addRow(rowText, prices);
  }

  if (prices.size === 0) {
    throw new ApiError("The Card Kingdom price feed held no usable prices.", 0);
  }

  catalogue = prices;
  fetchedAt = new Date().toISOString();

  try {
    // writeFile replaces the file whole, which is what keeps this a single list rather than an
    // accumulation of snapshots.
    await Filesystem.writeFile({
      path: CACHE_FILE,
      directory: Directory.Data,
      encoding: Encoding.UTF8,
      data: JSON.stringify({ fetchedAt, prices: Object.fromEntries(catalogue) } satisfies CacheFile),
    });
  } catch (err) {
    // The download itself succeeded; losing only the disk copy costs a re-download next launch.
    console.warn("Could not persist the Card Kingdom catalogue; it stays in memory only.", err);
  }
}

/** Tries the cache file. True when it yielded a catalogue, whatever its age. */
async function loadFromDisk(): Promise<boolean> {
  let contents: string;
  try {
    const file = await Filesystem.readFile({
      path: CACHE_FILE,
      directory: Directory.Data,
      encoding: Encoding.UTF8,
    });
    contents = typeof file.data === "string" ? file.data : await file.data.text();
  } catch {
    return false; // Nothing cached yet.
  }

  try {
    const cache = JSON.parse(contents) as CacheFile;
    if (typeof cache.fetchedAt !== "string" || typeof cache.prices !== "object") {
      throw new Error("The cache file does not have the expected shape.");
    }
    catalogue = new Map(Object.entries(cache.prices));
    fetchedAt = cache.fetchedAt;
    return true;
  } catch {
    // A half-written or corrupt cache is useless; delete it so the next load downloads fresh.
    catalogue = null;
    fetchedAt = null;
    try {
      await Filesystem.deleteFile({ path: CACHE_FILE, directory: Directory.Data });
    } catch {
      // Already gone.
    }
    return false;
  }
}

function isStale(): boolean {
  return (
    fetchedAt === null || Date.now() - new Date(fetchedAt).getTime() > MAX_AGE_MS
  );
}

export function isLoaded(): boolean {
  return catalogue !== null;
}

/** When the current catalogue was downloaded (ISO, UTC), or null before the first load. */
export function getFetchedAt(): string | null {
  return fetchedAt;
}

export function priceFor(printingId: string): VendorPrice | undefined {
  return catalogue?.get(printingId.toLowerCase());
}

/**
 * Makes the catalogue available, preferring memory, then the cache file, then the network. A
 * stale copy is still served when a re-download fails — prices from this morning beat no prices —
 * but a first-ever load with no network surfaces its error to the caller.
 */
export function ensureLoaded(): Promise<void> {
  if (inFlight !== null) {
    return inFlight;
  }
  if (catalogue !== null && !isStale()) {
    return Promise.resolve();
  }

  inFlight = (async () => {
    if (catalogue === null) {
      await loadFromDisk();
    }
    if (catalogue !== null && !isStale()) {
      return;
    }
    try {
      await download();
    } catch (err) {
      if (catalogue === null) {
        throw err;
      }
      console.warn("Card Kingdom refresh failed; keeping the stale catalogue.", err);
    }
  })().finally(() => {
    inFlight = null;
  });

  return inFlight;
}

/**
 * Downloads the catalogue again right now, replacing the single cached copy. Joins any download
 * already in flight rather than starting a second one.
 */
export function refresh(): Promise<void> {
  if (inFlight !== null) {
    return inFlight;
  }

  inFlight = download().finally(() => {
    inFlight = null;
  });

  return inFlight;
}
