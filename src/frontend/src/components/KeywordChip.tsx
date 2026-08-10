"use client";

import { useEffect, useState } from "react";
import { lookupKeyword } from "@/lib/keywords";

/**
 * A card keyword as a tappable chip: tapping opens a small popup with the glossary definition,
 * so nobody has to scroll to the glossary to learn what Ward does. Keywords the glossary does
 * not cover say so instead of pretending to know.
 *
 * One popup at a time: an invisible full-screen backdrop closes it on any outside tap, and
 * Escape closes it for keyboard users.
 */
export function KeywordChip({ keyword }: { keyword: string }) {
  const [open, setOpen] = useState(false);
  const entry = lookupKeyword(keyword);

  useEffect(() => {
    if (!open) {
      return;
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open]);

  return (
    <span className="relative inline-block">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        className="chip px-2.5 py-1 text-sm"
      >
        {keyword}
      </button>

      {open && (
        <>
          <button
            type="button"
            aria-label="Close definition"
            onClick={() => setOpen(false)}
            className="fixed inset-0 z-10 cursor-default"
          />
          <span
            role="tooltip"
            className="rise absolute left-0 top-full z-20 mt-2 w-72 max-w-[80vw] rounded-xl border border-purple/50 bg-surface p-3 shadow-xl shadow-black/50"
          >
            <span className="block text-purple-light font-bold mb-1">
              {entry?.name ?? keyword}
            </span>
            <span className="block text-foreground/90 text-sm">
              {entry === null
                ? "This mechanic isn't in the glossary yet."
                : entry.definition}
            </span>
          </span>
        </>
      )}
    </span>
  );
}
