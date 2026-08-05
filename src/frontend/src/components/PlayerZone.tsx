"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import {
  commanderTax,
  reminderLabel,
  REMINDER_PHASES,
  type Player,
  type Reminder,
  type ReminderPhase,
} from "@/lib/game";

/** Clockwise degrees a zone's content turns so it reads upright from its player's seat. */
export type SeatRotation = 0 | 90 | 180 | 270;

interface PlayerZoneProps {
  player: Player;
  /** CSS grid-area this zone occupies on the board. */
  area: string;
  rotate: SeatRotation;
  /** True when it is this player's turn: highlighted border and the End turn button. */
  isActive: boolean;
  /** Reminders anchored to this player's turn, due or not. */
  reminders: Reminder[];
  onAdjustLife: (delta: number) => void;
  onAdjustCasts: (delta: number) => void;
  onRename: (name: string) => void;
  /** Opens the commander picker for this player. */
  onPickCommander: () => void;
  onEndTurn: () => void;
  /** Moves the turn marker here — a correction, not a turn taken. */
  onSetActive: () => void;
  onAddReminder: (phase: ReminderPhase, text: string) => void;
  onDismissReminder: (reminderId: number) => void;
}

/** Held life buttons start repeating after this pause… */
const HOLD_DELAY_MS = 400;
/** …then step at this rate until released. */
const HOLD_INTERVAL_MS = 150;
/** How long the running life-change badge stays before resetting. */
const RECENT_RESET_MS = 1500;

/**
 * One player's slice of the board: commander art behind a big life total, the left/right halves
 * as −1/+1 targets (hold to repeat), and a name chip that flips the zone to its settings — name,
 * commander, and the commander-tax stepper.
 */
export function PlayerZone({
  player,
  area,
  rotate,
  isActive,
  reminders,
  onAdjustLife,
  onAdjustCasts,
  onRename,
  onPickCommander,
  onEndTurn,
  onSetActive,
  onAddReminder,
  onDismissReminder,
}: PlayerZoneProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [recent, setRecent] = useState(0);

  const holdRef = useRef<{
    timeout: ReturnType<typeof setTimeout> | null;
    interval: ReturnType<typeof setInterval> | null;
  }>({ timeout: null, interval: null });
  const recentTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Timers must not outlive the zone (a "New game" mid-hold, for instance).
  useEffect(
    () => () => {
      stopHold(holdRef.current);
      if (recentTimerRef.current !== null) {
        clearTimeout(recentTimerRef.current);
      }
    },
    [],
  );

  // A held button's interval closes over the onAdjustLife of the render that started it, but the
  // page updates state functionally, so even a stale closure adjusts the current life total.
  function step(delta: number) {
    onAdjustLife(delta);
    setRecent((current) => current + delta);
    if (recentTimerRef.current !== null) {
      clearTimeout(recentTimerRef.current);
    }
    recentTimerRef.current = setTimeout(() => setRecent(0), RECENT_RESET_MS);
  }

  function startHold(delta: number) {
    stopHold(holdRef.current);
    step(delta);
    holdRef.current.timeout = setTimeout(() => {
      holdRef.current.interval = setInterval(() => step(delta), HOLD_INTERVAL_MS);
    }, HOLD_DELAY_MS);
  }

  function endHold() {
    stopHold(holdRef.current);
  }

  return (
    <div
      style={{ gridArea: area }}
      className={`relative min-h-0 min-w-0 ${
        rotate === 90 || rotate === 270 ? "[container-type:size]" : ""
      }`}
    >
      <RotatedContent rotate={rotate}>
        {menuOpen ? (
          <ZoneMenu
            player={player}
            isActive={isActive}
            reminders={reminders}
            onAdjustCasts={onAdjustCasts}
            onRename={onRename}
            onPickCommander={onPickCommander}
            onSetActive={onSetActive}
            onAddReminder={onAddReminder}
            onDismissReminder={onDismissReminder}
            onClose={() => setMenuOpen(false)}
          />
        ) : (
          <div
            className={`relative h-full w-full overflow-hidden rounded-xl border bg-surface select-none ${
              isActive ? "border-orange" : "border-purple/30"
            }`}
            onContextMenu={(event) => event.preventDefault()}
          >
            {player.commander?.artCropUrl != null && (
              <Image
                src={player.commander.artCropUrl}
                alt=""
                fill
                className="object-cover opacity-35"
              />
            )}

            <button
              type="button"
              aria-label={`${player.name}: subtract life`}
              onPointerDown={() => startHold(-1)}
              onPointerUp={endHold}
              onPointerLeave={endHold}
              onPointerCancel={endHold}
              className="absolute inset-y-0 left-0 z-10 w-1/2 cursor-pointer touch-none"
            />
            <button
              type="button"
              aria-label={`${player.name}: add life`}
              onPointerDown={() => startHold(1)}
              onPointerUp={endHold}
              onPointerLeave={endHold}
              onPointerCancel={endHold}
              className="absolute inset-y-0 right-0 z-10 w-1/2 cursor-pointer touch-none"
            />

            <div className="pointer-events-none absolute inset-0 z-10 flex flex-col items-center justify-center">
              <span className="text-5xl sm:text-6xl font-bold text-foreground drop-shadow-[0_2px_4px_rgba(0,0,0,0.8)]">
                {player.life}
              </span>
              <span
                className={`h-5 text-sm font-semibold text-orange drop-shadow ${
                  recent === 0 ? "invisible" : ""
                }`}
              >
                {recent > 0 ? `+${recent}` : recent}
              </span>
            </div>

            <span className="pointer-events-none absolute left-2 top-1/2 z-10 -translate-y-1/2 text-2xl text-foreground/40">
              −
            </span>
            <span className="pointer-events-none absolute right-2 top-1/2 z-10 -translate-y-1/2 text-2xl text-foreground/40">
              +
            </span>

            <div className="absolute inset-x-0 top-1.5 z-20 flex justify-center">
              <button
                type="button"
                onClick={() => setMenuOpen(true)}
                className="max-w-[90%] truncate rounded-full border border-purple/40 bg-background/80 px-3 py-1 text-sm text-foreground hover:border-purple transition-colors cursor-pointer"
              >
                {player.name}
                {player.commanderCasts > 0 && (
                  <span className="text-purple-light"> · Tax {commanderTax(player)}</span>
                )}
              </button>
            </div>

            <div className="pointer-events-none absolute inset-x-0 bottom-1.5 z-20 flex flex-col items-center gap-1">
              {reminders.length > 0 && (
                <div className="flex max-w-[95%] flex-wrap justify-center gap-1">
                  {reminders.map((reminder) =>
                    reminder.due ? (
                      <button
                        key={reminder.id}
                        type="button"
                        onClick={() => onDismissReminder(reminder.id)}
                        title="Tap when done"
                        className="pointer-events-auto max-w-full truncate rounded-full bg-orange px-2 py-0.5 text-xs font-semibold text-background cursor-pointer"
                      >
                        {reminderLabel(reminder)}
                      </button>
                    ) : (
                      <span
                        key={reminder.id}
                        className="max-w-full truncate rounded-full border border-purple/40 bg-background/80 px-2 py-0.5 text-xs text-foreground/70"
                      >
                        {reminderLabel(reminder)}
                      </span>
                    ),
                  )}
                </div>
              )}
              {isActive && (
                <button
                  type="button"
                  onClick={onEndTurn}
                  className="pointer-events-auto rounded-full border border-orange/60 bg-background/80 px-3 py-1 text-sm font-semibold text-orange hover:bg-orange hover:text-background transition-colors cursor-pointer"
                >
                  End turn
                </button>
              )}
            </div>
          </div>
        )}
      </RotatedContent>
    </div>
  );
}

function stopHold(hold: {
  timeout: ReturnType<typeof setTimeout> | null;
  interval: ReturnType<typeof setInterval> | null;
}) {
  if (hold.timeout !== null) {
    clearTimeout(hold.timeout);
    hold.timeout = null;
  }
  if (hold.interval !== null) {
    clearInterval(hold.interval);
    hold.interval = null;
  }
}

/**
 * Turns the zone's content toward its player. 0/180 keep the cell's own box; 90/270 swap width
 * and height, so the content is sized from the cell's container-query units (100cqh × 100cqw)
 * and rotated about the cell's centre — pure CSS, no measuring.
 */
function RotatedContent({
  rotate,
  children,
}: {
  rotate: SeatRotation;
  children: React.ReactNode;
}) {
  if (rotate === 0) {
    return <div className="h-full w-full">{children}</div>;
  }
  if (rotate === 180) {
    return <div className="h-full w-full rotate-180">{children}</div>;
  }
  return (
    <div
      className={`absolute left-1/2 top-1/2 h-[100cqw] w-[100cqh] -translate-x-1/2 -translate-y-1/2 ${
        rotate === 90 ? "rotate-90" : "-rotate-90"
      }`}
    >
      {children}
    </div>
  );
}

/** The zone flipped to its settings: rename, commander, the tax stepper, turn, and reminders. */
function ZoneMenu({
  player,
  isActive,
  reminders,
  onAdjustCasts,
  onRename,
  onPickCommander,
  onSetActive,
  onAddReminder,
  onDismissReminder,
  onClose,
}: {
  player: Player;
  isActive: boolean;
  reminders: Reminder[];
  onAdjustCasts: (delta: number) => void;
  onRename: (name: string) => void;
  onPickCommander: () => void;
  onSetActive: () => void;
  onAddReminder: (phase: ReminderPhase, text: string) => void;
  onDismissReminder: (reminderId: number) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="flex h-full w-full flex-col gap-2 overflow-y-auto rounded-xl border border-orange/40 bg-background/95 p-3">
      <div className="flex items-center gap-2">
        <input
          type="text"
          value={player.name}
          onChange={(event) => onRename(event.target.value)}
          aria-label="Player name"
          className="min-w-0 flex-1 rounded border border-orange/30 bg-surface px-2 py-1 text-sm text-foreground focus:outline-none focus:border-orange transition-colors"
        />
        <button
          type="button"
          onClick={onClose}
          aria-label="Close player settings"
          className="rounded border border-purple/40 bg-surface px-2 py-1 text-sm text-foreground hover:border-purple transition-colors cursor-pointer"
        >
          ✕
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="truncate text-foreground/80">
          {player.commander?.name ?? "No commander"}
        </span>
        <button
          type="button"
          onClick={onPickCommander}
          className="rounded border border-purple/40 bg-surface px-2 py-1 text-foreground hover:border-purple transition-colors cursor-pointer"
        >
          {player.commander === null ? "Set commander" : "Change"}
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <span className="whitespace-nowrap text-purple-light">
          Tax <span className="font-bold text-orange">{commanderTax(player)}</span>
          <span className="text-foreground/50"> · cast ×{player.commanderCasts}</span>
        </span>
        <span className="flex gap-1">
          <button
            type="button"
            onClick={() => onAdjustCasts(-1)}
            aria-label="One fewer commander cast"
            className="rounded border border-purple/40 bg-surface px-2.5 py-0.5 text-foreground hover:border-purple transition-colors cursor-pointer"
          >
            −
          </button>
          <button
            type="button"
            onClick={() => onAdjustCasts(1)}
            aria-label="One more commander cast"
            className="rounded border border-purple/40 bg-surface px-2.5 py-0.5 text-foreground hover:border-purple transition-colors cursor-pointer"
          >
            +
          </button>
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-2 text-sm">
        {isActive ? (
          <span className="text-orange font-semibold">Taking their turn</span>
        ) : (
          <button
            type="button"
            onClick={onSetActive}
            className="rounded border border-purple/40 bg-surface px-2 py-1 text-foreground hover:border-purple transition-colors cursor-pointer"
          >
            It&apos;s their turn
          </button>
        )}
      </div>

      <ReminderEditor
        reminders={reminders}
        onAddReminder={onAddReminder}
        onDismissReminder={onDismissReminder}
      />
    </div>
  );
}

/**
 * The reminders anchored to this player's turn: each removable, plus the form that arms a new
 * one — "on their next turn, at this phase, do this".
 */
function ReminderEditor({
  reminders,
  onAddReminder,
  onDismissReminder,
}: {
  reminders: Reminder[];
  onAddReminder: (phase: ReminderPhase, text: string) => void;
  onDismissReminder: (reminderId: number) => void;
}) {
  const [phase, setPhase] = useState<ReminderPhase>("upkeep");
  const [text, setText] = useState("");

  function add() {
    onAddReminder(phase, text);
    setText("");
  }

  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <span className="text-purple-light">Next turn</span>
      {reminders.map((reminder) => (
        <div key={reminder.id} className="flex items-center gap-2">
          <span
            className={`min-w-0 flex-1 truncate ${
              reminder.due ? "font-semibold text-orange" : "text-foreground/80"
            }`}
          >
            {reminderLabel(reminder)}
          </span>
          <button
            type="button"
            onClick={() => onDismissReminder(reminder.id)}
            aria-label={`Remove reminder: ${reminderLabel(reminder)}`}
            className="rounded border border-purple/40 bg-surface px-2 py-0.5 text-foreground hover:border-purple transition-colors cursor-pointer"
          >
            ✕
          </button>
        </div>
      ))}
      <div className="flex items-center gap-1.5">
        <select
          value={phase}
          onChange={(event) => setPhase(event.target.value as ReminderPhase)}
          aria-label="Reminder phase"
          className="rounded border border-purple/40 bg-surface px-1.5 py-1 text-foreground focus:outline-none focus:border-purple transition-colors"
        >
          {REMINDER_PHASES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
        <input
          type="text"
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              add();
            }
          }}
          placeholder="Do what?"
          aria-label="Reminder text"
          className="min-w-0 flex-1 rounded border border-purple/40 bg-surface px-2 py-1 text-foreground focus:outline-none focus:border-purple transition-colors"
        />
        <button
          type="button"
          onClick={add}
          className="rounded border border-orange/40 bg-surface px-2 py-1 text-orange hover:border-orange transition-colors cursor-pointer"
        >
          Add
        </button>
      </div>
    </div>
  );
}
