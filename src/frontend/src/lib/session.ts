/**
 * Shared game sessions over the Mtg.Api relay: the app phone hosts its commander game, other
 * phones scan a QR code or type the short code and follow along live — each player managing
 * their own seat from their own screen instead of reaching across the table.
 *
 * The relay never understands the game: the host publishes its whole state (the same versioned
 * envelope the local save uses, so {@link parseGame} validates it on arrival), guests send
 * {@link SessionAction}s back, and the host applies them with its own game logic. Null when
 * NEXT_PUBLIC_SESSION_WS_URL is unset — mirroring cardAr and cachedVendors — so the UI hides
 * every sharing affordance where no relay is configured.
 */

import { parseGame, serializeGame, type GameState } from "@/lib/game";
import { parseSessionAction, type SessionAction } from "@/lib/sessionActions";

const wsUrl = process.env.NEXT_PUBLIC_SESSION_WS_URL;

/**
 * Where the web build of this app lives, for QR join links. Without it the QR code cannot be
 * offered and guests type the code into the site themselves.
 */
const webAppUrl = process.env.NEXT_PUBLIC_WEB_APP_URL;

const OPEN_TIMEOUT_MS = 8000;

export interface HostSessionCallbacks {
  /** A guest's validated action; the caller applies it to the game. */
  onAction: (action: SessionAction) => void;
  onGuestCount: (count: number) => void;
  /** The connection dropped unexpectedly; resume() re-attaches with the kept code and key. */
  onLost: () => void;
}

export interface HostSession {
  code: string;
  hostKey: string;
  /** Shares the current game with every guest; null tells them the game was cleared. */
  publish: (game: GameState | null) => void;
  /** Ends the session for every guest and closes the socket. */
  end: () => void;
}

export interface GuestSessionCallbacks {
  /** The host's latest game, already validated; null when the host cleared it. */
  onState: (game: GameState | null) => void;
  onHostPresence: (present: boolean) => void;
  /** The host ended the session; the connection is already closed. */
  onEnded: () => void;
  /** The connection dropped unexpectedly; joining again with the same code rejoins. */
  onLost: () => void;
}

export interface GuestSession {
  send: (action: SessionAction) => void;
  leave: () => void;
}

interface Wire {
  socket: WebSocket;
  /** Closes without firing onLost — for goodbyes the UI chose, not failures. */
  closeQuietly: () => void;
}

const ERROR_MESSAGES: Record<string, string> = {
  "not-found": "No game with that code was found. Check the code with the host.",
  "bad-request": "The session server did not accept the connection.",
};

/**
 * Opens a socket, sends the hello, and resolves on the server's first reply. Later messages go
 * to `route`; an unexpected close after that fires `onLost` exactly once.
 */
function connect(
  hello: object,
  route: (message: Record<string, unknown>) => void,
  onLost: () => void,
): Promise<{ wire: Wire; first: Record<string, unknown> }> {
  return new Promise((resolve, reject) => {
    if (wsUrl === undefined || wsUrl === "") {
      reject(new Error("No session server is configured."));
      return;
    }

    let settled = false;
    let quiet = false;
    const socket = new WebSocket(wsUrl);
    const wire: Wire = {
      socket,
      closeQuietly: () => {
        quiet = true;
        socket.close();
      },
    };

    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        quiet = true;
        socket.close();
        reject(new Error("The session server did not answer in time."));
      }
    }, OPEN_TIMEOUT_MS);

    socket.onopen = () => socket.send(JSON.stringify(hello));
    socket.onmessage = (event) => {
      const message = parseMessage(event.data);
      if (message === null) {
        return;
      }
      if (settled) {
        route(message);
        return;
      }
      settled = true;
      clearTimeout(timer);
      if (message.type === "error") {
        quiet = true;
        socket.close();
        const reason = typeof message.reason === "string" ? message.reason : "";
        reject(new Error(ERROR_MESSAGES[reason] ?? "The session server refused the connection."));
        return;
      }
      resolve({ wire, first: message });
    };
    socket.onclose = () => {
      clearTimeout(timer);
      if (!settled) {
        settled = true;
        reject(new Error("Could not reach the session server."));
        return;
      }
      if (!quiet) {
        onLost();
      }
    };
  });
}

function makeHostRouter(callbacks: HostSessionCallbacks): (message: Record<string, unknown>) => void {
  return (message) => {
    if (message.type === "action") {
      const action = parseSessionAction(message.payload);
      if (action !== null) {
        callbacks.onAction(action);
      }
    } else if (message.type === "presence" && typeof message.guestCount === "number") {
      callbacks.onGuestCount(message.guestCount);
    }
  };
}

function makeHostSession(wire: Wire, code: string, hostKey: string): HostSession {
  return {
    code,
    hostKey,
    publish: (game) => {
      if (wire.socket.readyState !== WebSocket.OPEN) {
        return;
      }
      // The local save's envelope rides as the payload, so guests reuse parseGame verbatim.
      const payload: unknown = game === null ? null : JSON.parse(serializeGame(game));
      wire.socket.send(JSON.stringify({ type: "state", payload }));
    },
    end: () => {
      if (wire.socket.readyState === WebSocket.OPEN) {
        wire.socket.send(JSON.stringify({ type: "end" }));
      }
      wire.closeQuietly();
    },
  };
}

async function host(callbacks: HostSessionCallbacks): Promise<HostSession> {
  const { wire, first } = await connect({ role: "host" }, makeHostRouter(callbacks), callbacks.onLost);
  if (first.type !== "created" || typeof first.code !== "string" || typeof first.hostKey !== "string") {
    wire.closeQuietly();
    throw new Error("The session server sent an unexpected greeting.");
  }
  return makeHostSession(wire, first.code, first.hostKey);
}

/** Re-attaches to a session this device created; guests kept waiting through the outage. */
async function resume(
  code: string,
  hostKey: string,
  callbacks: HostSessionCallbacks,
): Promise<HostSession> {
  const { wire, first } = await connect(
    { role: "host", code, hostKey },
    makeHostRouter(callbacks),
    callbacks.onLost,
  );
  if (first.type !== "resumed") {
    wire.closeQuietly();
    throw new Error("The session could not be resumed. Start sharing again for a new code.");
  }
  if (typeof first.guestCount === "number") {
    callbacks.onGuestCount(first.guestCount);
  }
  return makeHostSession(wire, code, hostKey);
}

async function join(code: string, callbacks: GuestSessionCallbacks): Promise<GuestSession> {
  let wire: Wire | null = null;
  const connected = await connect(
    { role: "guest", code },
    (message) => {
      switch (message.type) {
        case "state": {
          if (message.payload === null) {
            callbacks.onState(null);
            return;
          }
          const game = parseGame(JSON.stringify(message.payload));
          if (game !== null) {
            callbacks.onState(game);
          }
          return;
        }
        case "host-gone":
          callbacks.onHostPresence(false);
          return;
        case "host-back":
          callbacks.onHostPresence(true);
          return;
        case "session-ended":
          wire?.closeQuietly();
          callbacks.onEnded();
          return;
        default:
          return;
      }
    },
    callbacks.onLost,
  );
  wire = connected.wire;
  if (connected.first.type !== "joined") {
    wire.closeQuietly();
    throw new Error("The session server sent an unexpected greeting.");
  }
  return {
    send: (action) => {
      if (connected.wire.socket.readyState === WebSocket.OPEN) {
        connected.wire.socket.send(JSON.stringify({ type: "action", payload: action }));
      }
    },
    leave: () => connected.wire.closeQuietly(),
  };
}

/** The web page a scanned QR code opens, or null when no web deployment is configured. */
function joinUrl(code: string): string | null {
  if (webAppUrl === undefined || webAppUrl === "") {
    return null;
  }
  return `${webAppUrl.replace(/\/+$/, "")}/game?session=${encodeURIComponent(code)}`;
}

function parseMessage(data: unknown): Record<string, unknown> | null {
  if (typeof data !== "string") {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(data);
    return typeof parsed === "object" && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/** Session entry points, or null where no relay is configured so the UI hides sharing. */
export const gameSession =
  wsUrl !== undefined && wsUrl !== "" ? { host, resume, join, joinUrl } : null;
