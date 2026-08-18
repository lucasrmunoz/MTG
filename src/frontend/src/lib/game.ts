/**
 * Commander game state: one device on the table tracking every player's life total, chosen
 * commander, command-zone casts (tax is two generic mana per previous cast), whose turn it is,
 * and delayed-trigger reminders ("at the beginning of Alice's next draw step, do X").
 *
 * Pure data and helpers only — no React, no storage, no Capacitor. The /game route owns the
 * single mutable copy and persists it through {@link serializeGame}/{@link parseGame}; the AR
 * screen receives a snapshot through {@link toArPlayers} and its edits come back through
 * {@link applyArPlayers}.
 */

import type { ArGamePlayer } from "@/lib/ar";

/** The chosen commander printing, kept as data so the zone and AR need no further lookups. */
export interface CommanderCard {
  /** Scryfall printing id of the picked art. */
  id: string;
  name: string;
  /** Full-card scan; what the AR camera recognises. Null for the rare imageless printing. */
  imageUrl: string | null;
  /** Art-only crop for the player zone background. Null when Scryfall publishes no crop. */
  artCropUrl: string | null;
}

export interface Player {
  /** Stable seat number, 1-based; survives renames and commander changes. */
  id: number;
  name: string;
  life: number;
  commander: CommanderCard | null;
  /** Casts from the command zone so far; the next cast costs {@link commanderTax} more. */
  commanderCasts: number;
}

/**
 * How zones are arranged: "grid" keeps every zone upright for a device held in hand, "table"
 * rotates zones outward for a device lying flat between the players.
 */
export type GameLayout = "grid" | "table";

/** The turn moments a reminder can anchor to; coarse on purpose — no full phase stepper. */
export const REMINDER_PHASES = ["upkeep", "draw", "combat", "end step"] as const;
export type ReminderPhase = (typeof REMINDER_PHASES)[number];

/**
 * A delayed trigger noted at the table: fires on a specific player's next turn — theirs or an
 * opponent's — labelled with the phase it belongs to. "Due" flips when that turn arrives and
 * stays set until the reminder is dismissed as done.
 */
export interface Reminder {
  id: number;
  /** Whose turn the reminder fires on; any seat, not just the creator's. */
  playerId: number;
  phase: ReminderPhase;
  text: string;
  due: boolean;
}

export interface GameState {
  startingLife: number;
  layout: GameLayout;
  players: Player[];
  /** Seat whose turn it is. Defaults to seat 1; correctable from any zone's menu. */
  activePlayerId: number;
  /** Total turns taken, 1-based — every player's turn counts, as in multiplayer parlance. */
  turn: number;
  reminders: Reminder[];
  /** Next reminder id to hand out; ids stay unique across dismissals. */
  nextReminderId: number;
}

export const MIN_PLAYERS = 2;
export const MAX_PLAYERS = 6;
export const DEFAULT_STARTING_LIFE = 40;

export function createGame(
  playerCount: number,
  startingLife: number,
  layout: GameLayout,
): GameState {
  const count = clampPlayerCount(playerCount);
  return {
    startingLife,
    layout,
    players: Array.from({ length: count }, (_, index) => ({
      id: index + 1,
      name: `Player ${index + 1}`,
      life: startingLife,
      commander: null,
      commanderCasts: 0,
    })),
    activePlayerId: 1,
    turn: 1,
    reminders: [],
    nextReminderId: 1,
  };
}

/**
 * Ends the active player's turn: the next seat becomes active and the turn counter advances.
 * Two kinds of reminders come due at this boundary: everything anchored to the incoming
 * player's turn (it is now that turn), and the outgoing player's end-step reminders (their end
 * step just happened — this is how "your next end step" created mid-turn fires this turn).
 */
export function endTurn(game: GameState): GameState {
  const index = game.players.findIndex((player) => player.id === game.activePlayerId);
  // An unknown active id lands at index -1, so (-1 + 1) % n safely restarts at seat order.
  const next = game.players[(index + 1) % game.players.length];
  if (next === undefined) {
    return game;
  }
  return {
    ...game,
    activePlayerId: next.id,
    turn: game.turn + 1,
    reminders: game.reminders.map((reminder) =>
      reminder.playerId === next.id ||
      (reminder.playerId === game.activePlayerId && reminder.phase === "end step")
        ? { ...reminder, due: true }
        : reminder,
    ),
  };
}

/**
 * Manually moves the turn marker — a correction, so the turn counter stays put. The chosen
 * player's reminders still come due: it is their turn now, however we got here.
 */
export function setActivePlayer(game: GameState, playerId: number): GameState {
  if (!game.players.some((player) => player.id === playerId)) {
    return game;
  }
  return {
    ...game,
    activePlayerId: playerId,
    reminders: game.reminders.map((reminder) =>
      reminder.playerId === playerId ? { ...reminder, due: true } : reminder,
    ),
  };
}

export function addReminder(
  game: GameState,
  playerId: number,
  phase: ReminderPhase,
  text: string,
): GameState {
  return {
    ...game,
    reminders: [
      ...game.reminders,
      { id: game.nextReminderId, playerId, phase, text: text.trim(), due: false },
    ],
    nextReminderId: game.nextReminderId + 1,
  };
}

/** Dismissing means "done" — the reminder is gone, not archived. */
export function dismissReminder(game: GameState, reminderId: number): GameState {
  return {
    ...game,
    reminders: game.reminders.filter((reminder) => reminder.id !== reminderId),
  };
}

const PHASE_LABELS: Record<ReminderPhase, string> = {
  upkeep: "Upkeep",
  draw: "Draw",
  combat: "Combat",
  "end step": "End step",
};

/** "End step: exile the token", or just "End step" when no note was typed. */
export function reminderLabel(reminder: Reminder): string {
  const phase = PHASE_LABELS[reminder.phase];
  return reminder.text === "" ? phase : `${phase}: ${reminder.text}`;
}

/** Two generic mana per cast already made from the command zone. */
export function commanderTax(player: Player): number {
  return player.commanderCasts * 2;
}

/** Life is unclamped: cards like Angel's Grace make negative totals a real game state. */
export function adjustLife(game: GameState, playerId: number, delta: number): GameState {
  return updatePlayer(game, playerId, (player) => ({ ...player, life: player.life + delta }));
}

export function adjustCommanderCasts(
  game: GameState,
  playerId: number,
  delta: number,
): GameState {
  return updatePlayer(game, playerId, (player) => ({
    ...player,
    commanderCasts: Math.max(0, player.commanderCasts + delta),
  }));
}

export function setPlayerName(game: GameState, playerId: number, name: string): GameState {
  return updatePlayer(game, playerId, (player) => ({ ...player, name }));
}

/** Changing commanders keeps the cast count: the tax follows the player, not the card. */
export function setCommander(
  game: GameState,
  playerId: number,
  commander: CommanderCard | null,
): GameState {
  return updatePlayer(game, playerId, (player) => ({ ...player, commander }));
}

export function setLayout(game: GameState, layout: GameLayout): GameState {
  return { ...game, layout };
}

function updatePlayer(
  game: GameState,
  playerId: number,
  update: (player: Player) => Player,
): GameState {
  return {
    ...game,
    players: game.players.map((player) =>
      player.id === playerId ? update(player) : player,
    ),
  };
}

/** One player's cell in the board grid, plus how far its content turns to face its seat. */
export interface Seat {
  /** CSS grid-area name; must appear in the plan's rows. */
  area: string;
  /** Clockwise degrees so the zone reads upright from where this player sits. */
  rotate: 0 | 90 | 180 | 270;
}

export interface BoardPlan {
  /** grid-template-areas, one string per row. */
  rows: string[];
  /** grid-template-columns. */
  columns: string;
  /** One seat per player, in player order. */
  seats: Seat[];
}

const ONE_COLUMN = "minmax(0, 1fr)";
const TWO_COLUMNS = "repeat(2, minmax(0, 1fr))";
/** Side seats hold rotated content, so the middle column gets the extra width. */
const TABLE_COLUMNS = "minmax(0, 1fr) minmax(0, 1.4fr) minmax(0, 1fr)";

const BOTTOM: Seat = { area: "bottom", rotate: 0 };
const TOP: Seat = { area: "top", rotate: 180 };
const LEFT_1: Seat = { area: "l1", rotate: 90 };
const LEFT_2: Seat = { area: "l2", rotate: 90 };
const RIGHT_1: Seat = { area: "r1", rotate: 270 };
const RIGHT_2: Seat = { area: "r2", rotate: 270 };

const GRID_PLANS: Record<number, BoardPlan> = {
  2: { rows: ["g1", "g2"], columns: ONE_COLUMN, seats: gridSeats(2) },
  3: { rows: ["g1 g2", "g3 g3"], columns: TWO_COLUMNS, seats: gridSeats(3) },
  4: { rows: ["g1 g2", "g3 g4"], columns: TWO_COLUMNS, seats: gridSeats(4) },
  5: { rows: ["g1 g2", "g3 g4", "g5 g5"], columns: TWO_COLUMNS, seats: gridSeats(5) },
  6: { rows: ["g1 g2", "g3 g4", "g5 g6"], columns: TWO_COLUMNS, seats: gridSeats(6) },
};

/**
 * Seats fill around the table as players join: host at the bottom, then the sides, then the far
 * end — mirroring where people actually sit. A side with only one player keeps its whole column.
 */
const TABLE_PLANS: Record<number, BoardPlan> = {
  2: { rows: ["top", "bottom"], columns: ONE_COLUMN, seats: [BOTTOM, TOP] },
  3: {
    rows: ["l1 bottom r1"],
    columns: TABLE_COLUMNS,
    seats: [BOTTOM, LEFT_1, RIGHT_1],
  },
  4: {
    rows: ["l1 top r1", "l1 bottom r1"],
    columns: TABLE_COLUMNS,
    seats: [BOTTOM, LEFT_1, RIGHT_1, TOP],
  },
  5: {
    rows: ["l1 top r1", "l2 bottom r1"],
    columns: TABLE_COLUMNS,
    seats: [BOTTOM, LEFT_1, RIGHT_1, TOP, LEFT_2],
  },
  6: {
    rows: ["l1 top r1", "l2 bottom r2"],
    columns: TABLE_COLUMNS,
    seats: [BOTTOM, LEFT_1, RIGHT_1, TOP, LEFT_2, RIGHT_2],
  },
};

const EMPTY_PLAN: BoardPlan = { rows: [], columns: ONE_COLUMN, seats: [] };

export function boardPlan(layout: GameLayout, playerCount: number): BoardPlan {
  const plans = layout === "grid" ? GRID_PLANS : TABLE_PLANS;
  return plans[clampPlayerCount(playerCount)] ?? EMPTY_PLAN;
}

function gridSeats(count: number): Seat[] {
  return Array.from({ length: count }, (_, index) => ({ area: `g${index + 1}`, rotate: 0 }));
}

function clampPlayerCount(count: number): number {
  return Math.min(MAX_PLAYERS, Math.max(MIN_PLAYERS, Math.trunc(count)));
}

/**
 * Storage envelope. Versioned so a future shape change can migrate or discard old saves instead
 * of crashing on them.
 */
const STORAGE_VERSION = 2;

/** Version 1 predates turn tracking; its saves load with the turn fields defaulted. */
const LEGACY_STORAGE_VERSION = 1;

export function serializeGame(game: GameState): string {
  return JSON.stringify({ version: STORAGE_VERSION, game });
}

/** Null for anything unrecognised — a corrupt save starts a fresh setup, never a crash. */
export function parseGame(raw: string): GameState | null {
  let envelope: unknown;
  try {
    envelope = JSON.parse(raw);
  } catch {
    return null;
  }
  if (
    !isRecord(envelope) ||
    (envelope.version !== STORAGE_VERSION && envelope.version !== LEGACY_STORAGE_VERSION) ||
    !isRecord(envelope.game)
  ) {
    return null;
  }

  const { startingLife, layout, players } = envelope.game;
  if (
    typeof startingLife !== "number" ||
    (layout !== "grid" && layout !== "table") ||
    !Array.isArray(players) ||
    players.length < MIN_PLAYERS ||
    players.length > MAX_PLAYERS
  ) {
    return null;
  }

  const parsedPlayers: Player[] = [];
  for (const entry of players) {
    const player = parsePlayer(entry);
    if (player === null) {
      return null;
    }
    parsedPlayers.push(player);
  }

  const turnState = parseTurnState(envelope.game, parsedPlayers);
  if (turnState === null) {
    return null;
  }
  return { startingLife, layout, players: parsedPlayers, ...turnState };
}

type TurnState = Pick<GameState, "activePlayerId" | "turn" | "reminders" | "nextReminderId">;

/**
 * The turn fields, defaulted when absent so a version-1 save keeps its game. An active id no
 * seat owns falls back to the first seat instead of failing the whole save.
 */
function parseTurnState(game: Record<string, unknown>, players: Player[]): TurnState | null {
  const { activePlayerId, turn, reminders, nextReminderId } = game;
  const fallbackActive = players[0]?.id ?? 1;

  const parsedReminders: Reminder[] = [];
  if (reminders !== undefined) {
    if (!Array.isArray(reminders)) {
      return null;
    }
    for (const entry of reminders) {
      const reminder = parseReminder(entry);
      if (reminder === null) {
        return null;
      }
      parsedReminders.push(reminder);
    }
  }

  if (
    (activePlayerId !== undefined && typeof activePlayerId !== "number") ||
    (turn !== undefined && typeof turn !== "number") ||
    (nextReminderId !== undefined && typeof nextReminderId !== "number")
  ) {
    return null;
  }

  const active =
    typeof activePlayerId === "number" && players.some((player) => player.id === activePlayerId)
      ? activePlayerId
      : fallbackActive;
  const highestId = parsedReminders.reduce((max, reminder) => Math.max(max, reminder.id), 0);
  return {
    activePlayerId: active,
    turn: typeof turn === "number" ? Math.max(1, Math.trunc(turn)) : 1,
    reminders: parsedReminders,
    nextReminderId: Math.max(typeof nextReminderId === "number" ? nextReminderId : 1, highestId + 1),
  };
}

function parseReminder(entry: unknown): Reminder | null {
  if (!isRecord(entry)) {
    return null;
  }
  const { id, playerId, phase, text, due } = entry;
  if (
    typeof id !== "number" ||
    typeof playerId !== "number" ||
    typeof text !== "string" ||
    typeof due !== "boolean" ||
    !REMINDER_PHASES.includes(phase as ReminderPhase)
  ) {
    return null;
  }
  return { id, playerId, phase: phase as ReminderPhase, text, due };
}

function parsePlayer(entry: unknown): Player | null {
  if (!isRecord(entry)) {
    return null;
  }
  const { id, name, life, commander, commanderCasts } = entry;
  if (
    typeof id !== "number" ||
    typeof name !== "string" ||
    typeof life !== "number" ||
    typeof commanderCasts !== "number"
  ) {
    return null;
  }

  let parsedCommander: CommanderCard | null = null;
  if (commander !== null && commander !== undefined) {
    if (
      !isRecord(commander) ||
      typeof commander.id !== "string" ||
      typeof commander.name !== "string" ||
      !isNullableString(commander.imageUrl) ||
      !isNullableString(commander.artCropUrl)
    ) {
      return null;
    }
    parsedCommander = {
      id: commander.id,
      name: commander.name,
      imageUrl: commander.imageUrl ?? null,
      artCropUrl: commander.artCropUrl ?? null,
    };
  }

  return {
    id,
    name,
    life,
    commander: parsedCommander,
    commanderCasts: Math.max(0, commanderCasts),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNullableString(value: unknown): value is string | null | undefined {
  return value === null || value === undefined || typeof value === "string";
}

/**
 * The snapshot the AR screen works from. Players without a recognisable scan send card: null.
 * Turn and reminder info rides along as display data — the AR screen shows it but cannot
 * change it, so none of it is read back by {@link applyArPlayers}.
 */
export function toArPlayers(game: GameState): ArGamePlayer[] {
  return game.players.map((player) => ({
    id: player.id,
    name: player.name,
    life: player.life,
    commanderCasts: player.commanderCasts,
    active: player.id === game.activePlayerId,
    reminders: game.reminders
      .filter((reminder) => reminder.playerId === player.id)
      .map((reminder) => (reminder.due ? `❗ ${reminderLabel(reminder)}` : reminderLabel(reminder))),
    card:
      player.commander !== null && player.commander.imageUrl !== null
        ? {
            id: player.commander.id,
            name: player.commander.name,
            imageUrl: player.commander.imageUrl,
            artCropUrl: player.commander.artCropUrl,
          }
        : null,
  }));
}

/**
 * Merges what the AR session changed — life and casts only, matched by player id. Names,
 * commanders and layout always keep the web-side values, and ids the game does not know are
 * ignored, so a stale or malformed AR result can never corrupt the game.
 */
export function applyArPlayers(game: GameState, players: readonly ArGamePlayer[]): GameState {
  const returned = new Map(players.map((player) => [player.id, player]));
  return {
    ...game,
    players: game.players.map((player) => {
      const update = returned.get(player.id);
      if (update === undefined) {
        return player;
      }
      return {
        ...player,
        life: Number.isFinite(update.life) ? Math.trunc(update.life) : player.life,
        commanderCasts: Number.isFinite(update.commanderCasts)
          ? Math.max(0, Math.trunc(update.commanderCasts))
          : player.commanderCasts,
      };
    }),
  };
}
