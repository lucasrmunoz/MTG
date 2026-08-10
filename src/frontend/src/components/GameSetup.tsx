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
    <div className="panel rise p-4 sm:p-6 max-w-md mx-auto">
      <h2 className="section-title mb-4">New Game</h2>

      <p className="text-purple-light text-sm mb-2">Players</p>
      <div className="flex gap-2 mb-5">
        {PLAYER_COUNTS.map((count) => (
          <button
            key={count}
            type="button"
            onClick={() => setPlayerCount(count)}
            aria-pressed={count === playerCount}
            className={`btn flex-1 px-0 ${count === playerCount ? "btn-primary" : "btn-ghost"}`}
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
            className={`btn ${preset === startingLife ? "btn-primary" : "btn-ghost"}`}
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
          className="field min-w-0 flex-1"
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
        className="btn btn-primary w-full py-3 text-base"
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
      className={`rounded-[0.625rem] border px-4 py-2 text-left transition-all duration-150 cursor-pointer ${
        selected
          ? "border-orange bg-orange/10 shadow-[0_0_0_1px_var(--orange)]"
          : "border-purple/40 bg-background-deep/40 hover:border-purple-light hover:bg-surface-raised"
      }`}
    >
      <span className={`block font-semibold ${selected ? "text-orange" : "text-foreground"}`}>
        {title}
      </span>
      <span className="block text-sm text-foreground/60">{hint}</span>
    </button>
  );
}
