"use client";

import { useState } from "react";
import { CardImage } from "@/components/CardImage";
import {
  boardRow,
  commanderTax,
  type BoardCard,
  type BoardRow,
  type GameState,
  type Player,
} from "@/lib/game";

/**
 * The top-down table: every player's scanned cards lined up by row — creatures, lands, then
 * everything else — with their counters and the flying marker. Read-only by design: the AR
 * screen is the sole writer of boards, this view just shows what it tracked. The filter is
 * per-device; each viewer picks whose side they are studying.
 */
export function BoardView({ game }: { game: GameState }) {
  const [filterPlayerId, setFilterPlayerId] = useState<number | null>(null);

  const shown = game.players.filter(
    (player) => filterPlayerId === null || player.id === filterPlayerId,
  );

  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      <div className="flex shrink-0 flex-wrap gap-2">
        <FilterChip
          label="All players"
          selected={filterPlayerId === null}
          onSelect={() => setFilterPlayerId(null)}
        />
        {game.players.map((player) => (
          <FilterChip
            key={player.id}
            label={player.name}
            selected={filterPlayerId === player.id}
            onSelect={() => setFilterPlayerId(player.id)}
          />
        ))}
      </div>

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pb-2">
        {shown.map((player) => (
          <PlayerBoard
            key={player.id}
            player={player}
            isActive={player.id === game.activePlayerId}
          />
        ))}
      </div>
    </div>
  );
}

function FilterChip({
  label,
  selected,
  onSelect,
}: {
  label: string;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`btn btn-sm ${selected ? "btn-primary" : "btn-ghost"}`}
    >
      {label}
    </button>
  );
}

const ROWS: { row: BoardRow; label: string }[] = [
  { row: "creatures", label: "Creatures" },
  { row: "lands", label: "Lands" },
  { row: "other", label: "Other" },
];

function PlayerBoard({ player, isActive }: { player: Player; isActive: boolean }) {
  const byRow = new Map<BoardRow, BoardCard[]>();
  for (const card of player.board) {
    const row = boardRow(card);
    const cards = byRow.get(row);
    if (cards === undefined) {
      byRow.set(row, [card]);
    } else {
      cards.push(card);
    }
  }

  return (
    <section
      className={`panel p-3 ${player.eliminated ? "opacity-50" : ""} ${
        isActive ? "border-orange" : ""
      }`}
    >
      <header className="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="font-display font-bold text-purple-light">{player.name}</h3>
        <span className="text-sm text-foreground/60">
          {player.life} life
          {player.commander !== null &&
            ` · ${player.commander.name} (tax ${commanderTax(player)})`}
        </span>
      </header>

      {player.board.length === 0 ? (
        <p className="text-sm text-foreground/40">
          Nothing scanned to this side of the table yet.
        </p>
      ) : (
        ROWS.map(({ row, label }) => {
          const cards = byRow.get(row);
          if (cards === undefined) {
            return null;
          }
          return (
            <div key={row} className="mb-2 last:mb-0">
              <p className="mb-1 text-xs uppercase tracking-wider text-foreground/40">{label}</p>
              <div className="flex flex-wrap gap-2">
                {cards.map((card) => (
                  <BoardCardTile key={card.id} card={card} />
                ))}
              </div>
            </div>
          );
        })
      )}
    </section>
  );
}

function BoardCardTile({ card }: { card: BoardCard }) {
  const statDelta =
    card.power !== 0 || card.toughness !== 0
      ? `${card.power >= 0 ? `+${card.power}` : card.power}/${
          card.toughness >= 0 ? `+${card.toughness}` : card.toughness
        }`
      : null;
  // Flying shows as its own marker; remaining keyword counters collapse into a count.
  const otherKeywords = card.keywords.filter((keyword) => keyword.toLowerCase() !== "flying");

  return (
    <figure className="relative w-24" title={card.name}>
      {card.imageUrl !== null ? (
        <CardImage
          src={card.imageUrl}
          alt={card.name}
          width={96}
          height={134}
          foil={false}
          className="rounded-md"
        />
      ) : (
        <div className="flex h-[134px] w-24 items-center justify-center rounded-md border border-purple/40 bg-background-deep/60 p-1 text-center text-xs">
          {card.name}
        </div>
      )}

      <div className="absolute left-0.5 top-0.5 flex flex-col items-start gap-0.5">
        {card.tokenCount > 0 && <Badge>×{card.tokenCount}</Badge>}
        {statDelta !== null && <Badge>{statDelta}</Badge>}
        {card.flying && <Badge>✈ Flying</Badge>}
        {otherKeywords.length > 0 && (
          <Badge>{otherKeywords.length === 1 ? otherKeywords[0] : `${otherKeywords.length} counters`}</Badge>
        )}
      </div>
    </figure>
  );
}

function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded bg-background-deep/85 px-1 py-0.5 text-[10px] font-bold text-orange">
      {children}
    </span>
  );
}
