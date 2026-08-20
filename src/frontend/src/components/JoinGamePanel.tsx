"use client";

import { useState } from "react";

interface JoinGamePanelProps {
  onJoin: (code: string) => void;
  joining: boolean;
  error: string | null;
}

/**
 * The web guest's front door: type the code from the host's share overlay. Scanned QR codes
 * skip this panel entirely — their link carries the code and joins on arrival.
 */
export function JoinGamePanel({ onJoin, joining, error }: JoinGamePanelProps) {
  const [code, setCode] = useState("");
  const trimmed = code.trim();

  return (
    <div className="panel rise mx-auto max-w-md p-4 sm:p-6">
      <h2 className="section-title mb-2">Join a game</h2>
      <p className="mb-4 text-sm text-foreground/60">
        Scan the host&rsquo;s QR code, or type the game code from their share screen. Hosting a
        game happens in the app.
      </p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (trimmed !== "" && !joining) {
            onJoin(trimmed);
          }
        }}
        className="flex gap-2"
      >
        <input
          value={code}
          onChange={(event) => setCode(event.target.value.toUpperCase())}
          placeholder="e.g. XK7PQ2"
          aria-label="Game code"
          autoCapitalize="characters"
          autoComplete="off"
          spellCheck={false}
          maxLength={6}
          className="field min-w-0 flex-1 font-mono tracking-widest"
        />
        <button type="submit" disabled={trimmed === "" || joining} className="btn btn-primary">
          {joining ? "Joining…" : "Join"}
        </button>
      </form>

      {error !== null && <div className="banner-error mt-4 p-3 text-sm">{error}</div>}
    </div>
  );
}
