"use client";

import Link from "next/link";
import { notFound } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { CommanderPicker } from "@/components/CommanderPicker";
import { GameBoard } from "@/components/GameBoard";
import { GameSetup } from "@/components/GameSetup";
import { JoinGamePanel } from "@/components/JoinGamePanel";
import { ShareGameControls } from "@/components/ShareGameControls";
import { cardAr } from "@/lib/ar";
import {
  addReminder,
  adjustCommanderCasts,
  adjustLife,
  applyArPlayers,
  createGame,
  dismissReminder,
  endTurn,
  setActivePlayer,
  setCommander,
  setLayout,
  setPlayerEliminated,
  setPlayerName,
  serializeGame,
  parseGame,
  toArPlayers,
  type GameLayout,
  type GameState,
  type ReminderPhase,
} from "@/lib/game";
import { gameSession, type GuestSession } from "@/lib/session";
import { applySessionAction, type SessionAction } from "@/lib/sessionActions";

/**
 * Hosting a game ships only in the app build. On the web this route is the guest's door into a
 * session shared from an app — and stays a 404 where no session relay is configured.
 */
const isMobileApp = process.env.NEXT_PUBLIC_MOBILE_APP === "true";

/** The one saved game. Versioned inside the payload, not the key. */
const STORAGE_KEY = "mtg.game.v1";

/** How long "New game" waits for its confirming second tap. */
const CONFIRM_RESET_MS = 2500;

export default function GamePage() {
  return isMobileApp ? <AppGamePage /> : <WebGamePage />;
}

/** The host experience: the local game this device owns, optionally shared as a session. */
function AppGamePage() {
  const [game, setGame] = useState<GameState | null>(null);
  // Gates the persistence effect until the saved game has been read back, so the initial null
  // state cannot wipe a save the page simply has not restored yet.
  const [restored, setRestored] = useState(false);
  const [pickerFor, setPickerFor] = useState<number | null>(null);
  const [confirmingReset, setConfirmingReset] = useState(false);
  const [arBusy, setArBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const saved = raw === null ? null : parseGame(raw);
    /* eslint-disable react-hooks/set-state-in-effect -- storage is client-only, so the saved
       game can only be read after hydration; a synchronous set right here is that pattern. */
    if (saved !== null) {
      setGame(saved);
    }
    setRestored(true);
    /* eslint-enable react-hooks/set-state-in-effect */
  }, []);

  useEffect(() => {
    if (!restored) {
      return;
    }
    if (game === null) {
      window.localStorage.removeItem(STORAGE_KEY);
    } else {
      window.localStorage.setItem(STORAGE_KEY, serializeGame(game));
    }
  }, [game, restored]);

  useWakeLock(game !== null);

  useEffect(() => {
    if (!confirmingReset) {
      return;
    }
    const timer = setTimeout(() => setConfirmingReset(false), CONFIRM_RESET_MS);
    return () => clearTimeout(timer);
  }, [confirmingReset]);

  const handleAdjustLife = useCallback((playerId: number, delta: number) => {
    setGame((current) => (current === null ? current : adjustLife(current, playerId, delta)));
  }, []);
  const handleAdjustCasts = useCallback((playerId: number, delta: number) => {
    setGame((current) =>
      current === null ? current : adjustCommanderCasts(current, playerId, delta),
    );
  }, []);
  const handleRename = useCallback((playerId: number, name: string) => {
    setGame((current) => (current === null ? current : setPlayerName(current, playerId, name)));
  }, []);
  const handleEndTurn = useCallback(() => {
    setGame((current) => (current === null ? current : endTurn(current)));
  }, []);
  const handleSetActive = useCallback((playerId: number) => {
    setGame((current) => (current === null ? current : setActivePlayer(current, playerId)));
  }, []);
  const handleSetEliminated = useCallback((playerId: number, eliminated: boolean) => {
    setGame((current) =>
      current === null ? current : setPlayerEliminated(current, playerId, eliminated),
    );
  }, []);
  const handleAddReminder = useCallback(
    (playerId: number, phase: ReminderPhase, text: string) => {
      setGame((current) =>
        current === null ? current : addReminder(current, playerId, phase, text),
      );
    },
    [],
  );
  const handleDismissReminder = useCallback((reminderId: number) => {
    setGame((current) => (current === null ? current : dismissReminder(current, reminderId)));
  }, []);
  // A guest's action lands in the same state updater its own buttons use; the publish effect in
  // ShareGameControls then carries the result back to every guest.
  const handleSessionAction = useCallback((action: SessionAction) => {
    setGame((current) => (current === null ? current : applySessionAction(current, action)));
  }, []);

  function handleNewGame() {
    if (!confirmingReset) {
      setConfirmingReset(true);
      return;
    }
    setConfirmingReset(false);
    setPickerFor(null);
    setGame(null);
  }

  /**
   * Hands the players to the native AR screen and merges back what it changed (life and casts
   * only). A rejected bridge call leaves the game exactly as it was — the game is never lost to
   * an AR failure.
   */
  async function handleOpenAr() {
    if (cardAr === null || game === null || arBusy) {
      return;
    }
    setArBusy(true);
    setError(null);
    try {
      const result = await cardAr.openGame(toArPlayers(game));
      setGame((current) =>
        current === null ? current : applyArPlayers(current, result.players),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not open the AR view.");
    } finally {
      setArBusy(false);
    }
  }

  if (!restored) {
    return null;
  }

  if (game === null) {
    return (
      <div className="min-h-screen p-4 sm:p-6 lg:p-8">
        <div className="mx-auto max-w-7xl">
          <div className="mb-6 flex items-center justify-between gap-4">
            <h1 className="font-display text-2xl sm:text-3xl font-bold bg-gradient-to-r from-orange-hover via-orange to-purple-light bg-clip-text text-transparent">
              Commander Game
            </h1>
            <Link href="/" className="btn btn-ghost btn-sm">
              ← Card lookup
            </Link>
          </div>
          <GameSetup
            onStart={(playerCount, startingLife, layout) =>
              setGame(createGame(playerCount, startingLife, layout))
            }
          />
        </div>
      </div>
    );
  }

  const pickerPlayer = game.players.find((player) => player.id === pickerFor) ?? null;

  return (
    <div className="flex h-dvh flex-col gap-2 overflow-hidden p-2">
      <div className="flex shrink-0 flex-wrap items-center gap-2">
        <Link href="/" aria-label="Back to card lookup" className="btn btn-ghost btn-sm px-2.5">
          ←
        </Link>
        <LayoutToggle layout={game.layout} onChange={(next) => setGame(setLayout(game, next))} />
        <span className="whitespace-nowrap font-display text-sm font-bold tracking-wider text-purple-light">
          Turn {game.turn}
        </span>
        <span className="min-w-0 flex-1" />
        {gameSession !== null && (
          <ShareGameControls game={game} onAction={handleSessionAction} />
        )}
        {cardAr !== null && (
          <button
            type="button"
            onClick={() => void handleOpenAr()}
            disabled={arBusy}
            className="btn btn-ghost btn-sm"
          >
            {arBusy ? "In AR…" : "View in AR"}
          </button>
        )}
        <button
          type="button"
          onClick={handleNewGame}
          className={`btn btn-sm ${confirmingReset ? "btn-danger" : "btn-ghost"}`}
        >
          {confirmingReset ? "Tap again to end" : "New game"}
        </button>
      </div>

      {error !== null && (
        <div className="banner-error shrink-0 px-3 py-1.5 text-sm">{error}</div>
      )}

      <div className="min-h-0 flex-1">
        <GameBoard
          game={game}
          onAdjustLife={handleAdjustLife}
          onAdjustCasts={handleAdjustCasts}
          onRename={handleRename}
          onPickCommander={setPickerFor}
          onEndTurn={handleEndTurn}
          onSetActive={handleSetActive}
          onSetEliminated={handleSetEliminated}
          onAddReminder={handleAddReminder}
          onDismissReminder={handleDismissReminder}
        />
      </div>

      {pickerPlayer !== null && (
        <CommanderPicker
          player={pickerPlayer}
          onPick={(commander) => {
            setGame((current) =>
              current === null ? current : setCommander(current, pickerPlayer.id, commander),
            );
            setPickerFor(null);
          }}
          onClose={() => setPickerFor(null)}
        />
      )}
    </div>
  );
}

/**
 * The guest experience: a session joined by code — from a scanned QR link's ?session= or typed
 * by hand — rendering the host's game and sending every edit back as an action. The host is
 * authoritative, so the board only moves when its state comes back over the wire.
 */
function WebGamePage() {
  if (gameSession === null) {
    notFound();
  }

  const [code, setCode] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  const [hostPresent, setHostPresent] = useState(true);
  const [ended, setEnded] = useState(false);
  const [lost, setLost] = useState(false);
  const [pickerFor, setPickerFor] = useState<number | null>(null);
  // The host's layout is their table's arrangement; this device is in someone's hand, so the
  // guest keeps a local override instead of following it.
  const [layoutOverride, setLayoutOverride] = useState<GameLayout | null>(null);

  const sessionRef = useRef<GuestSession | null>(null);
  // Re-entry guard the callback can trust: the `joining` state is stale inside its closure, and
  // a double-tapped Rejoin must not open two sockets.
  const joiningRef = useRef(false);

  const joinByCode = useCallback(async (rawCode: string) => {
    if (gameSession === null || joiningRef.current) {
      return;
    }
    const normalized = rawCode.trim().toUpperCase();
    if (normalized === "") {
      return;
    }
    sessionRef.current?.leave();
    joiningRef.current = true;
    setJoining(true);
    setJoinError(null);
    setEnded(false);
    setLost(false);
    setHostPresent(true);
    try {
      const joined = await gameSession.join(normalized, {
        onState: setGame,
        onHostPresence: setHostPresent,
        onEnded: () => {
          setEnded(true);
          setGame(null);
        },
        onLost: () => setLost(true),
      });
      sessionRef.current = joined;
      setCode(normalized);
    } catch (err) {
      setJoinError(err instanceof Error ? err.message : "Could not join the game.");
    } finally {
      joiningRef.current = false;
      setJoining(false);
    }
  }, []);

  // A scanned QR code lands here with ?session=CODE; join it without any typing.
  useEffect(() => {
    const fromUrl = new URLSearchParams(window.location.search).get("session");
    if (fromUrl !== null && fromUrl !== "") {
      /* eslint-disable-next-line react-hooks/set-state-in-effect -- the URL is client-only, so
         the code can only be read after hydration; joining right here is that pattern. */
      void joinByCode(fromUrl);
    }
  }, [joinByCode]);

  useEffect(
    () => () => {
      sessionRef.current?.leave();
    },
    [],
  );

  useWakeLock(game !== null);

  const send = useCallback((action: SessionAction) => {
    sessionRef.current?.send(action);
  }, []);

  const handleAdjustLife = useCallback(
    (playerId: number, delta: number) => send({ kind: "adjustLife", playerId, delta }),
    [send],
  );
  const handleAdjustCasts = useCallback(
    (playerId: number, delta: number) => send({ kind: "adjustCasts", playerId, delta }),
    [send],
  );
  const handleRename = useCallback(
    (playerId: number, name: string) => send({ kind: "rename", playerId, name }),
    [send],
  );
  const handleEndTurn = useCallback(() => send({ kind: "endTurn" }), [send]);
  const handleSetActive = useCallback(
    (playerId: number) => send({ kind: "setActive", playerId }),
    [send],
  );
  const handleSetEliminated = useCallback(
    (playerId: number, eliminated: boolean) => send({ kind: "setEliminated", playerId, eliminated }),
    [send],
  );
  const handleAddReminder = useCallback(
    (playerId: number, phase: ReminderPhase, text: string) =>
      send({ kind: "addReminder", playerId, phase, text }),
    [send],
  );
  const handleDismissReminder = useCallback(
    (reminderId: number) => send({ kind: "dismissReminder", reminderId }),
    [send],
  );

  function handleLeave() {
    sessionRef.current?.leave();
    sessionRef.current = null;
    setCode(null);
    setGame(null);
    setEnded(false);
    setLost(false);
  }

  if (game === null) {
    return (
      <div className="min-h-screen p-4 sm:p-6 lg:p-8">
        <div className="mx-auto max-w-7xl">
          <div className="mb-6 flex items-center justify-between gap-4">
            <h1 className="font-display text-2xl sm:text-3xl font-bold bg-gradient-to-r from-orange-hover via-orange to-purple-light bg-clip-text text-transparent">
              Commander Game
            </h1>
            <Link href="/" className="btn btn-ghost btn-sm">
              ← Card lookup
            </Link>
          </div>
          {ended && (
            <div className="banner-error mx-auto mb-4 max-w-md p-3 text-sm">
              The host ended the game.
            </div>
          )}
          {code !== null && !ended && (
            <div className="panel mx-auto mb-4 max-w-md p-3 text-sm text-foreground/60">
              Joined game {code} — waiting for the host to share the board…
            </div>
          )}
          <JoinGamePanel onJoin={(entered) => void joinByCode(entered)} joining={joining} error={joinError} />
        </div>
      </div>
    );
  }

  const pickerPlayer = game.players.find((player) => player.id === pickerFor) ?? null;
  const layout = layoutOverride ?? game.layout;

  return (
    <div className="flex h-dvh flex-col gap-2 overflow-hidden p-2">
      <div className="flex shrink-0 flex-wrap items-center gap-2">
        <LayoutToggle layout={layout} onChange={setLayoutOverride} />
        <span className="whitespace-nowrap font-display text-sm font-bold tracking-wider text-purple-light">
          Turn {game.turn}
        </span>
        {code !== null && (
          <span className="whitespace-nowrap font-mono text-sm tracking-widest text-foreground/60">
            {code}
          </span>
        )}
        <span className="min-w-0 flex-1" />
        <button type="button" onClick={handleLeave} className="btn btn-ghost btn-sm">
          Leave
        </button>
      </div>

      {lost && (
        <div className="banner-error flex shrink-0 items-center gap-3 px-3 py-1.5 text-sm">
          <span>Connection lost.</span>
          <button
            type="button"
            onClick={() => code !== null && void joinByCode(code)}
            className="btn btn-danger btn-sm"
          >
            Rejoin
          </button>
        </div>
      )}
      {!lost && !hostPresent && (
        <div className="banner-error shrink-0 px-3 py-1.5 text-sm">
          The host disconnected — the board stays as it was until they return.
        </div>
      )}

      <div className="min-h-0 flex-1">
        <GameBoard
          game={{ ...game, layout }}
          onAdjustLife={handleAdjustLife}
          onAdjustCasts={handleAdjustCasts}
          onRename={handleRename}
          onPickCommander={setPickerFor}
          onEndTurn={handleEndTurn}
          onSetActive={handleSetActive}
          onSetEliminated={handleSetEliminated}
          onAddReminder={handleAddReminder}
          onDismissReminder={handleDismissReminder}
        />
      </div>

      {pickerPlayer !== null && (
        <CommanderPicker
          player={pickerPlayer}
          onPick={(commander) => {
            send({ kind: "setCommander", playerId: pickerPlayer.id, commander });
            setPickerFor(null);
          }}
          onClose={() => setPickerFor(null)}
        />
      )}
    </div>
  );
}

function LayoutToggle({
  layout,
  onChange,
}: {
  layout: GameLayout;
  onChange: (layout: GameLayout) => void;
}) {
  return (
    <div className="flex overflow-hidden rounded-[0.625rem] border border-purple/40 bg-background-deep/60">
      {(
        [
          ["grid", "Grid"],
          ["table", "Table"],
        ] as const
      ).map(([value, label]) => (
        <button
          key={value}
          type="button"
          onClick={() => onChange(value)}
          aria-pressed={layout === value}
          className={`px-2.5 py-1 text-sm font-semibold transition-colors duration-150 cursor-pointer ${
            layout === value
              ? "bg-gradient-to-b from-orange-hover to-orange text-background-deep"
              : "text-foreground hover:text-orange"
          }`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * Keeps the screen awake while a game is on — a life tracker lying on the table must not go
 * dark mid-game. Best effort: where the Wake Lock API is missing or refuses, the screen simply
 * dims as usual. The lock releases itself whenever the page is hidden, so it is re-requested
 * each time the page becomes visible again.
 */
function useWakeLock(active: boolean) {
  useEffect(() => {
    if (!active || !("wakeLock" in navigator)) {
      return;
    }

    let sentinel: WakeLockSentinel | null = null;
    let cancelled = false;

    async function acquire() {
      try {
        const lock = await navigator.wakeLock.request("screen");
        if (cancelled) {
          void lock.release();
        } else {
          sentinel = lock;
        }
      } catch {
        // Denied (power saver, unsupported): the screen dimming is the graceful fallback.
      }
    }

    void acquire();
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        void acquire();
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", onVisibilityChange);
      if (sentinel !== null) {
        void sentinel.release();
      }
    };
  }, [active]);
}
