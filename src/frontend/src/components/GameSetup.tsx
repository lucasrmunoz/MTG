"use client";

import { useState } from "react";
import {
  DEFAULT_STARTING_LIFE,
  MAX_PLAYERS,
  MIN_PLAYERS,
  type GameLayout,
} from "@/lib/game";

interface GameSetupProps {
  onStart: (playerCount: number, startingLife: number, layout: GameLayout) => void;
}

const PLAYER_COUNTS = Array.from(
  { length: MAX_PLAYERS - MIN_PLAYERS + 1 },
  (_, index) => MIN_PLAYERS + index,
);

const LIFE_PRESETS = [20, 30, 40];

/** Everything a new game needs up front; names and commanders are set per zone afterwards. */
export function GameSetup({ onStart }: GameSetupProps) {
  const [playerCount, setPlayerCount] = useState(4);
  const [startingLife, setStartingLife] = useState(DEFAULT_STARTING_LIFE);
  const [layout, setLayout] = useState<GameLayout>("grid");

  return (
    <div className="bg-surface rounded-lg border border-purple/30 p-4 sm:p-6 max-w-md mx-auto">
      <h2 className="text-orange font-semibold text-sm uppercase tracking-wide mb-4">
        New Game
      </h2>

      <p className="text-purple-light text-sm mb-2">Players</p>
      <div className="flex gap-2 mb-5">
        {PLAYER_COUNTS.map((count) => (
          <button
            key={count}
            type="button"
            onClick={() => setPlayerCount(count)}
            aria-pressed={count === playerCount}
            className={`flex-1 rounded border px-0 py-2 font-semibold transition-colors cursor-pointer ${
              count === playerCount
                ? "bg-orange text-background border-orange"
                : "bg-background border-purple/40 hover:border-purple text-foreground"
            }`}
          >
            {count}
          </button>
        ))}
      </div>

      <p className="text-purple-light text-sm mb-2">Starting life</p>
      <div className="flex gap-2 mb-5">
        {LIFE_PRESETS.map((preset) => (
          <button
            key={preset}
            type="button"
            onClick={() => setStartingLife(preset)}
            aria-pressed={preset === startingLife}
            className={`rounded border px-4 py-2 font-semibold transition-colors cursor-pointer ${
              preset === startingLife
                ? "bg-orange text-background border-orange"
                : "bg-background border-purple/40 hover:border-purple text-foreground"
            }`}
          >
            {preset}
          </button>
        ))}
        <input
          type="number"
          value={startingLife}
          onChange={(event) => {
            const value = Number(event.target.value);
            if (Number.isFinite(value)) {
              setStartingLife(Math.trunc(value));
            }
          }}
          aria-label="Custom starting life"
          className="min-w-0 flex-1 bg-background border border-orange/30 rounded px-3 py-2 text-foreground focus:outline-none focus:border-orange transition-colors"
        />
      </div>

      <p className="text-purple-light text-sm mb-2">Layout</p>
      <div className="flex flex-col gap-2 mb-6">
        <LayoutOption
          selected={layout === "grid"}
          title="Grid"
          hint="Every zone upright — for a device held in hand or propped up."
          onSelect={() => setLayout("grid")}
        />
        <LayoutOption
          selected={layout === "table"}
          title="Around the table"
          hint="Zones rotate outward — for a device lying flat between the players."
          onSelect={() => setLayout("table")}
        />
      </div>

      <button
        type="button"
        onClick={() => onStart(playerCount, startingLife, layout)}
        className="w-full bg-orange hover:bg-orange-hover text-background font-semibold px-4 py-3 rounded transition-colors cursor-pointer"
      >
        Start game
      </button>
    </div>
  );
}

function LayoutOption({
  selected,
  title,
  hint,
  onSelect,
}: {
  selected: boolean;
  title: string;
  hint: string;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`rounded border px-4 py-2 text-left transition-colors cursor-pointer ${
        selected
          ? "border-orange bg-background"
          : "border-purple/40 bg-background/50 hover:border-purple"
      }`}
    >
      <span className={`block font-semibold ${selected ? "text-orange" : "text-foreground"}`}>
        {title}
      </span>
      <span className="block text-sm text-foreground/60">{hint}</span>
    </button>
  );
}
