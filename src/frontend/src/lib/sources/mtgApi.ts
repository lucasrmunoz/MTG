/**
 * Client for this project's own Mtg.Api.
 *
 * Used when NEXT_PUBLIC_API_BASE_URL is set. Adds the two vendors a browser cannot reach on its
 * own: Mana Pool (no CORS headers) and Card Kingdom (catalogue-only, 66 MB).
 */

import { ApiError } from "@/lib/errors";
import { NO_FILTERS, type SearchFilters } from "@/lib/search";
import type { ArtVersion, CardSearchResult, VendorInfo } from "@/lib/types";

/** RFC 9457 problem details, which is how Mtg.Api reports every failure. */
interface ProblemDetails {
  title?: string;
  detail?: string;
  status?: number;
}

async function readProblemDetail(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as ProblemDetails;
    return (
      problem.detail ??
      problem.title ??
      `Request failed with status ${response.status}.`
    );
  } catch {
    return `Request failed with status ${response.status}.`;
  }
}

/** The search query string. Filters are sent by id; the API knows what each one means. */
function searchParams(name: string, filters: SearchFilters): URLSearchParams {
  const params = new URLSearchParams({ name });
  if (filters.type !== null) {
    params.set("type", filters.type);
  }
  if (filters.colors.length > 0) {
    params.set("colors", filters.colors.join(""));
    params.set("colorMode", filters.colorMode);
  }
  for (const trait of filters.landTraits) {
    params.append("landTraits", trait);
  }
  return params;
}

function createClient(baseUrl: string) {
  async function getJson<T>(path: string): Promise<T> {
    let response: Response;
    try {
      response = await fetch(`${baseUrl}${path}`);
    } catch {
      throw new ApiError(`Could not reach the card API at ${baseUrl}. Is it running?`, 0);
    }

    if (!response.ok) {
      throw new ApiError(await readProblemDetail(response), response.status);
    }

    return (await response.json()) as T;
  }

  return {
    searchCards: (name: string, filters: SearchFilters = NO_FILTERS) =>
      getJson<CardSearchResult>(`/api/cards/search?${searchParams(name, filters)}`),

    fetchArtVersions: (name: string) =>
      getJson<ArtVersion[]>(`/api/cards/art?name=${encodeURIComponent(name)}`),

    fetchVendors: () => getJson<VendorInfo[]>("/api/vendors"),
  };
}

export { createClient };
