"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { commanderTax, type Player } from "@/lib/game";

/** Clockwise degrees a zone's content turns so it reads upright from its player's seat. */
export type SeatRotation = 0 | 90 | 180 | 270;

interface PlayerZoneProps {
  player: Player;
  /** CSS grid-area this zone occupies on the board. */
  area: string;
  rotate: SeatRotation;
  onAdjustLife: (delta: number) => void;
  onAdjustCasts: (delta: number) => void;
  onRename: (name: string) => void;
  /** Opens the commander picker for this player. */
  onPickCommander: () => void;
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
  onAdjustLife,
  onAdjustCasts,
  onRename,
  onPickCommander,
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
            onAdjustCasts={onAdjustCasts}
            onRename={onRename}
            onPickCommander={onPickCommander}
            onClose={() => setMenuOpen(false)}
          />
        ) : (
          <div
            className="relative h-full w-full overflow-hidden rounded-xl border border-purple/30 bg-surface select-none"
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

/** The zone flipped to its settings: rename, commander, and the tax stepper. */
function ZoneMenu({
  player,
  onAdjustCasts,
  onRename,
  onPickCommander,
  onClose,
}: {
  player: Player;
  onAdjustCasts: (delta: number) => void;
  onRename: (name: string) => void;
  onPickCommander: () => void;
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
    </div>
  );
}
