/**
 * Actions a session guest asks the host to apply. The host phone is authoritative: it validates
 * each action that arrives off the network here, applies it through the same game.ts helpers its
 * own UI uses, and shares the resulting state back. Any participant may act on any seat — the
 * same trust as dice on a physical table — so authority is about the host serialising changes,
 * not about permissions.
 */

import {
  addReminder,
  adjustCommanderCasts,
  adjustLife,
  dismissReminder,
  endTurn,
  REMINDER_PHASES,
  setActivePlayer,
  setCommander,
  setPlayerEliminated,
  setPlayerName,
  type CommanderCard,
  type GameState,
  type ReminderPhase,
} from "@/lib/game";

export type SessionAction =
  | { kind: "adjustLife"; playerId: number; delta: number }
  | { kind: "adjustCasts"; playerId: number; delta: number }
  | { kind: "rename"; playerId: number; name: string }
  | { kind: "setCommander"; playerId: number; commander: CommanderCard | null }
  | { kind: "endTurn" }
  | { kind: "setActive"; playerId: number }
  | { kind: "setEliminated"; playerId: number; eliminated: boolean }
  | { kind: "addReminder"; playerId: number; phase: ReminderPhase; text: string }
  | { kind: "dismissReminder"; reminderId: number };

/** Wider than any real button press; only a malformed client sends more at once. */
const MAX_DELTA = 999;
const MAX_NAME_LENGTH = 60;
const MAX_REMINDER_LENGTH = 200;

/**
 * Applies one already-validated action. Unknown seats and ids are the helpers' problem — every
 * game.ts function ignores ids it does not know, so a stale action is a no-op, never a crash.
 */
export function applySessionAction(game: GameState, action: SessionAction): GameState {
  switch (action.kind) {
    case "adjustLife":
      return adjustLife(game, action.playerId, action.delta);
    case "adjustCasts":
      return adjustCommanderCasts(game, action.playerId, action.delta);
    case "rename":
      return setPlayerName(game, action.playerId, action.name);
    case "setCommander":
      return setCommander(game, action.playerId, action.commander);
    case "endTurn":
      return endTurn(game);
    case "setActive":
      return setActivePlayer(game, action.playerId);
    case "setEliminated":
      return setPlayerEliminated(game, action.playerId, action.eliminated);
    case "addReminder":
      return addReminder(game, action.playerId, action.phase, action.text);
    case "dismissReminder":
      return dismissReminder(game, action.reminderId);
  }
}

/** Null for anything unrecognised or out of bounds — a bad frame is dropped, never applied. */
export function parseSessionAction(value: unknown): SessionAction | null {
  if (!isRecord(value)) {
    return null;
  }

  switch (value.kind) {
    case "adjustLife":
    case "adjustCasts":
      return isSeatId(value.playerId) && isDelta(value.delta)
        ? { kind: value.kind, playerId: value.playerId, delta: value.delta }
        : null;
    case "rename":
      return isSeatId(value.playerId) &&
        typeof value.name === "string" &&
        value.name.length <= MAX_NAME_LENGTH
        ? { kind: "rename", playerId: value.playerId, name: value.name }
        : null;
    case "setCommander": {
      if (!isSeatId(value.playerId)) {
        return null;
      }
      const commander = parseCommander(value.commander);
      return commander === undefined
        ? null
        : { kind: "setCommander", playerId: value.playerId, commander };
    }
    case "endTurn":
      return { kind: "endTurn" };
    case "setActive":
      return isSeatId(value.playerId) ? { kind: "setActive", playerId: value.playerId } : null;
    case "setEliminated":
      return isSeatId(value.playerId) && typeof value.eliminated === "boolean"
        ? { kind: "setEliminated", playerId: value.playerId, eliminated: value.eliminated }
        : null;
    case "addReminder":
      return isSeatId(value.playerId) &&
        REMINDER_PHASES.includes(value.phase as ReminderPhase) &&
        typeof value.text === "string" &&
        value.text.length <= MAX_REMINDER_LENGTH
        ? {
            kind: "addReminder",
            playerId: value.playerId,
            phase: value.phase as ReminderPhase,
            text: value.text,
          }
        : null;
    case "dismissReminder":
      return Number.isInteger(value.reminderId) && typeof value.reminderId === "number"
        ? { kind: "dismissReminder", reminderId: value.reminderId }
        : null;
    default:
      return null;
  }
}

/** The commander field: undefined signals invalid, distinct from a valid explicit null (clear). */
function parseCommander(value: unknown): CommanderCard | null | undefined {
  if (value === null) {
    return null;
  }
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.name !== "string" ||
    !isNullableString(value.imageUrl) ||
    !isNullableString(value.artCropUrl)
  ) {
    return undefined;
  }
  return {
    id: value.id,
    name: value.name,
    imageUrl: value.imageUrl,
    artCropUrl: value.artCropUrl,
  };
}

function isSeatId(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function isDelta(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && Math.abs(value) <= MAX_DELTA;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}
