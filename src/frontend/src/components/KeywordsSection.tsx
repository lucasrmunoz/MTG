"use client";

import { useState } from "react";
import { KEYWORDS, type KeywordEntry } from "@/lib/keywords";

/**
 * Self-contained keyword glossary: a random-keyword spotlight plus a collapsible full list.
 * All data is local, so nothing here touches the network.
 */
export function KeywordsSection() {
  const [spotlight, setSpotlight] = useState<KeywordEntry | null>(null);
  const [showAll, setShowAll] = useState(false);

  function pickRandom() {
    const entry = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
    if (entry !== undefined) {
      setSpotlight(entry);
    }
  }

  return (
    <div className="bg-surface rounded-lg border border-purple/30 p-4 sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <h2 className="text-orange font-semibold text-sm uppercase tracking-wide">
          Keyword Glossary
        </h2>

        <div className="flex flex-wrap gap-3">
          <button
            type="button"
            onClick={pickRandom}
            className="bg-purple hover:bg-purple-light text-background font-semibold px-4 py-2 rounded text-sm transition-colors cursor-pointer"
          >
            Random Keyword
          </button>
          <button
            type="button"
            onClick={() => setShowAll((current) => !current)}
            aria-expanded={showAll}
            className="bg-background border border-orange/30 hover:border-orange text-foreground font-semibold px-4 py-2 rounded text-sm transition-colors cursor-pointer"
          >
            {showAll ? "Hide All Keywords" : `Show All Keywords (${KEYWORDS.length})`}
          </button>
        </div>
      </div>

      {spotlight === null && !showAll && (
        <p className="text-foreground/50 text-sm">
          Draw a random keyword, or open the full list of {KEYWORDS.length} keywords and what
          they do.
        </p>
      )}

      {spotlight !== null && (
        <div className="bg-background rounded-lg border border-purple/40 p-4 mb-4">
          <h3 className="text-purple-light font-bold text-lg mb-1">{spotlight.name}</h3>
          <p className="text-foreground">{spotlight.definition}</p>
        </div>
      )}

      {showAll && (
        <dl className="grid gap-x-8 gap-y-3 sm:grid-cols-2">
          {KEYWORDS.map((entry) => (
            <div key={entry.name}>
              <dt className="text-purple-light font-semibold">{entry.name}</dt>
              <dd className="text-foreground/80 text-sm">{entry.definition}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}
