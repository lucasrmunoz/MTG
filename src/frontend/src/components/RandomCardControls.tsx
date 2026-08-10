"use client";

import { useState } from "react";
import { COLOR_MATCH_MODES, COLORS, type ColorMatchMode } from "@/lib/colors";

interface RandomCardControlsProps {
  loading: boolean;
  onRandom: (colors: string[], mode: ColorMatchMode) => void;
}

/**
 * Color filter and button for loading a random card. The selection lives here because the page
 * only needs it at the moment the button is pressed.
 */
export function RandomCardControls({ loading, onRandom }: RandomCardControlsProps) {
  const [selected, setSelected] = useState<string[]>([]);
  const [mode, setMode] = useState<ColorMatchMode>("contains");

  function toggleColor(id: string) {
    setSelected((current) =>
      current.includes(id) ? current.filter((color) => color !== id) : [...current, id],
    );
  }

  return (
    <div className="panel p-4 sm:p-6 mb-6 sm:mb-8">
      <h2 className="section-title mb-3">Random Card</h2>

      <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
        <div className="flex flex-wrap gap-2" role="group" aria-label="Color filter">
          {COLORS.map((color) => {
            const active = selected.includes(color.id);
            return (
              <button
                key={color.id}
                type="button"
                aria-pressed={active}
                onClick={() => toggleColor(color.id)}
                className={`btn btn-sm ${active ? "btn-primary" : "btn-ghost"}`}
              >
                {color.label}
              </button>
            );
          })}
        </div>

        <select
          value={mode}
          onChange={(event) => setMode(event.target.value as ColorMatchMode)}
          disabled={selected.length === 0}
          aria-label="Color match mode"
          className="field min-w-0"
        >
          {COLOR_MATCH_MODES.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>

        <button
          type="button"
          disabled={loading}
          onClick={() => onRandom(selected, mode)}
          className="btn btn-violet px-4 sm:px-6"
        >
          {loading ? (
            <>
              <span className="spinner" />
              Drawing...
            </>
          ) : (
            "Surprise Me"
          )}
        </button>
      </div>

      <p className="text-foreground/50 text-xs mt-3">
        {selected.length === 0
          ? "No colors selected — any card in Magic can turn up."
          : mode === "contains"
            ? "The card will have every selected color, and may have others."
            : "The card will have no colors outside the selection."}
      </p>
    </div>
  );
}
