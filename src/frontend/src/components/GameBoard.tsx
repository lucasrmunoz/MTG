"use client";

import { PlayerZone } from "@/components/PlayerZone";
import { boardPlan, type GameState, type ReminderPhase } from "@/lib/game";

interface GameBoardProps {
  game: GameState;
  onAdjustLife: (playerId: number, delta: number) => void;
  onAdjustCasts: (playerId: number, delta: number) => void;
  onRename: (playerId: number, name: string) => void;
  onPickCommander: (playerId: number) => void;
  onEndTurn: () => void;
  onSetActive: (playerId: number) => void;
  onSetEliminated: (playerId: number, eliminated: boolean) => void;
  onAddReminder: (playerId: number, phase: ReminderPhase, text: string) => void;
  onDismissReminder: (reminderId: number) => void;
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
  onEndTurn,
  onSetActive,
  onSetEliminated,
  onAddReminder,
  onDismissReminder,
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
            isActive={player.id === game.activePlayerId}
            reminders={game.reminders.filter((reminder) => reminder.playerId === player.id)}
            onAdjustLife={(delta) => onAdjustLife(player.id, delta)}
            onAdjustCasts={(delta) => onAdjustCasts(player.id, delta)}
            onRename={(name) => onRename(player.id, name)}
            onPickCommander={() => onPickCommander(player.id)}
            onEndTurn={onEndTurn}
            onSetActive={() => onSetActive(player.id)}
            onSetEliminated={(eliminated) => onSetEliminated(player.id, eliminated)}
            onAddReminder={(phase, text) => onAddReminder(player.id, phase, text)}
            onDismissReminder={onDismissReminder}
          />
        );
      })}
    </div>
  );
}
