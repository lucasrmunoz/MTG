/**
 * Glossary of Magic keyword abilities and keyword actions.
 *
 * Definitions are written in reminder-text style rather than quoting the Comprehensive Rules —
 * no public API serves keyword definitions, so the list is maintained by hand. It covers every
 * evergreen keyword plus the widely played set mechanics; it is not the exhaustive list of
 * everything ever printed.
 *
 * The data lives in public/keywords.json so a single copy serves both worlds: this module for
 * the web UI, and the Android app's AR screen, which reads the same file out of its bundled web
 * assets (assets/public/keywords.json after `cap sync`).
 */

import keywordData from "@public/keywords.json";

export interface KeywordEntry {
  name: string;
  definition: string;
}

/** Alphabetical, so the full glossary reads like a dictionary. */
export const KEYWORDS: readonly KeywordEntry[] = keywordData;

/**
 * The glossary entry for a keyword as Scryfall names it, or null for mechanics the glossary
 * does not cover. Case-insensitive: Scryfall says "First strike", the glossary "First strike",
 * players type "first strike".
 */
export function lookupKeyword(name: string): KeywordEntry | null {
  const wanted = name.trim().toLowerCase();
  return KEYWORDS.find((entry) => entry.name.toLowerCase() === wanted) ?? null;
}
