"use client";

import { PlayerZone } from "@/components/PlayerZone";
import { boardPlan, type GameState } from "@/lib/game";

interface GameBoardProps {
  game: GameState;
  onAdjustLife: (playerId: number, delta: number) => void;
  onAdjustCasts: (playerId: number, delta: number) => void;
  onRename: (playerId: number, name: string) => void;
  onPickCommander: (playerId: number) => void;
}

/**
 * The live board: one zone per player, arranged by the layout's seat plan. The plan is data —
 * grid-template strings and per-seat rotations — so this component never knows which layout it
 * is rendering.
 */
export function GameBoard({
  game,
  onAdjustLife,
  onAdjustCasts,
  onRename,
  onPickCommander,
}: GameBoardProps) {
  const plan = boardPlan(game.layout, game.players.length);

  return (
    <div
      className="grid h-full min-h-0 gap-2"
      style={{
        gridTemplateAreas: plan.rows.map((row) => `"${row}"`).join(" "),
        gridTemplateColumns: plan.columns,
        gridTemplateRows: `repeat(${plan.rows.length}, minmax(0, 1fr))`,
      }}
    >
      {game.players.map((player, index) => {
        const seat = plan.seats[index];
        if (seat === undefined) {
          return null;
        }
        return (
          <PlayerZone
            key={player.id}
            player={player}
            area={seat.area}
            rotate={seat.rotate}
            onAdjustLife={(delta) => onAdjustLife(player.id, delta)}
            onAdjustCasts={(delta) => onAdjustCasts(player.id, delta)}
            onRename={(name) => onRename(player.id, name)}
            onPickCommander={() => onPickCommander(player.id)}
          />
        );
      })}
    </div>
  );
}
