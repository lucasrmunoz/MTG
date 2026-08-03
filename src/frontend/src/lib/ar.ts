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
}

interface CardArPlugin {
  open(options: OpenCardArOptions): Promise<void>;
  scan(): Promise<void>;
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
        return plugin.open({
          ...options,
          printings: options.printings.slice(0, MAX_REFERENCE_IMAGES),
        });
      },
      /**
       * Opens the scanner with no card chosen: on-device OCR reads titles off the camera, each
       * confirmed against Scryfall's fuzzy lookup, and the user taps the card they meant.
       */
      scan(): Promise<void> {
        return plugin.scan();
      },
    }
  : null;
