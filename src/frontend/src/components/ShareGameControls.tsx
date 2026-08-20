"use client";

import QRCode from "qrcode";
import { useEffect, useRef, useState } from "react";
import { gameSession, type HostSession, type HostSessionCallbacks } from "@/lib/session";
import type { SessionAction } from "@/lib/sessionActions";
import type { GameState } from "@/lib/game";

interface ShareGameControlsProps {
  game: GameState;
  /** A guest's action for the host game; the page applies and re-publishes it. */
  onAction: (action: SessionAction) => void;
}

type ShareStatus = "idle" | "connecting" | "live" | "lost";

/**
 * Hosts a shared session for the current game: a header chip that starts sharing, shows the
 * join code and guest count while live, and opens an overlay with the QR code guests scan.
 * Unmounting — leaving the game screen or starting a new game — ends the session for everyone,
 * so a session never outlives the board it mirrors.
 */
export function ShareGameControls({ game, onAction }: ShareGameControlsProps) {
  const [session, setSession] = useState<HostSession | null>(null);
  const [status, setStatus] = useState<ShareStatus>("idle");
  const [guestCount, setGuestCount] = useState(0);
  const [overlayOpen, setOverlayOpen] = useState(false);
  // Keyed by code so a stale image can never show against a newer session's code.
  const [qr, setQr] = useState<{ code: string; dataUrl: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  // The session outlives individual renders; the ref lets callbacks and the unmount cleanup
  // reach the current one without re-subscribing anything.
  const sessionRef = useRef<HostSession | null>(null);
  const onActionRef = useRef(onAction);
  useEffect(() => {
    onActionRef.current = onAction;
  }, [onAction]);

  useEffect(
    () => () => {
      sessionRef.current?.end();
    },
    [],
  );

  // Every game change reaches the guests; publish() itself no-ops on a closed socket.
  useEffect(() => {
    if (status === "live" && session !== null) {
      session.publish(game);
    }
  }, [game, session, status]);

  useEffect(() => {
    if (!overlayOpen || session === null || gameSession === null) {
      return;
    }
    const url = gameSession.joinUrl(session.code);
    if (url === null) {
      return;
    }
    const code = session.code;
    let cancelled = false;
    QRCode.toDataURL(url, { width: 260, margin: 1 })
      .then((dataUrl) => {
        if (!cancelled) {
          setQr({ code, dataUrl });
        }
      })
      .catch(() => {
        // The overlay falls back to the typed code; nothing to clean up.
      });
    return () => {
      cancelled = true;
    };
  }, [overlayOpen, session]);

  if (gameSession === null) {
    return null;
  }

  const callbacks: HostSessionCallbacks = {
    onAction: (action) => onActionRef.current(action),
    onGuestCount: setGuestCount,
    onLost: () => setStatus("lost"),
  };

  async function handleStart() {
    if (gameSession === null) {
      return;
    }
    setStatus("connecting");
    setError(null);
    try {
      const started = await gameSession.host(callbacks);
      sessionRef.current = started;
      setSession(started);
      setGuestCount(0);
      setStatus("live");
      setOverlayOpen(true);
    } catch (err) {
      setStatus("idle");
      setError(err instanceof Error ? err.message : "Could not start sharing.");
    }
  }

  async function handleReconnect() {
    const lost = sessionRef.current;
    if (gameSession === null || lost === null) {
      return;
    }
    setStatus("connecting");
    setError(null);
    try {
      const resumed = await gameSession.resume(lost.code, lost.hostKey, callbacks);
      sessionRef.current = resumed;
      setSession(resumed);
      setStatus("live");
      // The guests waited through the outage on stale state; catch them up right away.
      resumed.publish(game);
    } catch (err) {
      sessionRef.current = null;
      setSession(null);
      setStatus("idle");
      setError(err instanceof Error ? err.message : "Could not resume the session.");
    }
  }

  function handleStop() {
    sessionRef.current?.end();
    sessionRef.current = null;
    setSession(null);
    setStatus("idle");
    setOverlayOpen(false);
    setGuestCount(0);
  }

  return (
    <>
      {status === "idle" && (
        <button type="button" onClick={() => void handleStart()} className="btn btn-ghost btn-sm">
          Share
        </button>
      )}
      {status === "connecting" && (
        <button type="button" disabled className="btn btn-ghost btn-sm">
          Sharing…
        </button>
      )}
      {status === "live" && session !== null && (
        <button
          type="button"
          onClick={() => setOverlayOpen(true)}
          className="btn btn-ghost btn-sm font-mono tracking-widest"
        >
          {session.code} · {guestCount}
        </button>
      )}
      {status === "lost" && (
        <button
          type="button"
          onClick={() => void handleReconnect()}
          className="btn btn-danger btn-sm"
        >
          Reconnect
        </button>
      )}
      {error !== null && <span className="text-sm text-foreground/60">{error}</span>}

      {overlayOpen && session !== null && (
        <ShareOverlay
          code={session.code}
          joinUrl={gameSession.joinUrl(session.code)}
          qrDataUrl={qr !== null && qr.code === session.code ? qr.dataUrl : null}
          guestCount={guestCount}
          onStop={handleStop}
          onClose={() => setOverlayOpen(false)}
        />
      )}
    </>
  );
}

function ShareOverlay({
  code,
  joinUrl,
  qrDataUrl,
  guestCount,
  onStop,
  onClose,
}: {
  code: string;
  joinUrl: string | null;
  qrDataUrl: string | null;
  guestCount: number;
  onStop: () => void;
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
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Share this game"
      className="fixed inset-0 z-40 overflow-y-auto bg-background-deep/95 backdrop-blur-sm p-4 sm:p-6"
    >
      <div className="panel rise mx-auto max-w-sm p-5 text-center">
        <h2 className="section-title mb-4">Share this game</h2>

        {qrDataUrl !== null ? (
          // A QR code is dense binary art; alt text describing the link serves screen readers.
          // eslint-disable-next-line @next/next/no-img-element -- a data: URL needs no optimising
          <img
            src={qrDataUrl}
            alt={`QR code to join game ${code}`}
            className="mx-auto mb-4 rounded-lg bg-white p-2"
            width={260}
            height={260}
          />
        ) : (
          <p className="mb-4 text-sm text-foreground/60">
            Guests join by typing the code into the web app.
          </p>
        )}

        <p className="mb-1 text-sm text-purple-light">Game code</p>
        <p className="mb-4 font-mono text-3xl font-bold tracking-[0.3em]">{code}</p>

        {joinUrl !== null && (
          <p className="mb-4 break-all text-xs text-foreground/60">{joinUrl}</p>
        )}

        <p className="mb-5 text-sm text-foreground/60">
          {guestCount === 1 ? "1 guest connected" : `${guestCount} guests connected`}
        </p>

        <div className="flex justify-center gap-2">
          <button type="button" onClick={onStop} className="btn btn-danger btn-sm">
            Stop sharing
          </button>
          {/* Focus lands on the harmless action; "Stop sharing" must be a deliberate tap. */}
          <button type="button" onClick={onClose} className="btn btn-ghost btn-sm" autoFocus>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
