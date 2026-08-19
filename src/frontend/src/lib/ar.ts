/**
 * Bridge to the app's native AR screen.
 *
 * The screen finds a known physical card with the camera — ARCore tracks reference images
 * registered at runtime from the card's printings, never arbitrary card-shaped objects — then
 * shows a zoomable floating copy with its counters, which persist on the device per printing.
 * Android-only: the capability is null on the web and in API mode, mirroring cachedVendors, so
 * the UI hides its button there. The native side lives in android/…/ar/.
 */

import { registerPlugin } from "@capacitor/core";

/** One printing the camera should recognise: Scryfall id plus its full-card image. */
export interface ArPrinting {
  id: string;
  imageUrl: string;
}

export interface OpenCardArOptions {
  /** Printing id of the card as opened, used when no physical card has been recognised yet. */
  cardId: string;
  cardName: string;
  /** Full-card image to float; the "normal" Scryfall scan. */
  imageUrl: string;
  /** Printings to register as reference images, so whichever copy is on the table is found. */
  printings: ArPrinting[];
  /** The card's own keywords, so the AR screen can offer their glossary definitions. */
  keywords: string[];
}

/** A commander the game session's camera should recognise. */
export interface ArGameCard {
  /** Scryfall printing id of the chosen art. */
  id: string;
  name: string;
  imageUrl: string;
  /** Art-only crop for the AR life token; null when Scryfall publishes none. */
  artCropUrl: string | null;
}

/**
 * One player as the AR game session sees them. The same shape crosses the bridge in both
 * directions; only life and commanderCasts are trusted coming back (merged by id on the web
 * side), so the native screen cannot rename players or swap commanders.
 */
export interface ArGamePlayer {
  id: number;
  name: string;
  life: number;
  /** Tax is always derived as 2 × casts — only the cast count crosses the bridge. */
  commanderCasts: number;
  /** True when it is this player's turn. Display-only in AR; never merged back. */
  active: boolean;
  /** Reminder labels for this player's badge, due ones marked ❗. Display-only in AR. */
  reminders: string[];
  /** Null when the player has no commander or the printing has no scan; shown but untracked. */
  card: ArGameCard | null;
}

export interface ArGameResult {
  players: ArGamePlayer[];
}

interface CardArPlugin {
  open(options: OpenCardArOptions): Promise<void>;
  openGame(options: { players: ArGamePlayer[] }): Promise<ArGameResult>;
}

const plugin = registerPlugin<CardArPlugin>("CardAr");

const isMobileApp = process.env.NEXT_PUBLIC_MOBILE_APP === "true";

/**
 * Reference-image registration slows down as the database grows, so only the most relevant
 * printings ride along: the one being viewed plus the earliest art versions.
 */
const MAX_REFERENCE_IMAGES = 12;

/** AR entry point, or null where no AR exists (web, API mode) so the UI hides the button. */
export const cardAr = isMobileApp
  ? {
      open(options: OpenCardArOptions): Promise<void> {
        // The viewed printing is the copy most likely on the table, so it must survive the cap
        // even when it sorts beyond the first MAX_REFERENCE_IMAGES by release date.
        const viewed = options.printings.find((printing) => printing.id === options.cardId);
        const printings =
          viewed === undefined
            ? options.printings
            : [viewed, ...options.printings.filter((printing) => printing.id !== viewed.id)];
        return plugin.open({
          ...options,
          printings: printings.slice(0, MAX_REFERENCE_IMAGES),
        });
      },
      /**
       * Opens the game session: every recognised commander gets a badge with its owner's name,
       * life and tax, adjustable in place. Resolves when the screen closes with the players'
       * updated values — or the originals, when it closes without changes (back, no camera).
       */
      openGame(players: ArGamePlayer[]): Promise<ArGameResult> {
        return plugin.openGame({ players });
      },
    }
  : null;
