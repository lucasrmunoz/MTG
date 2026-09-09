"use client";

import { CardImage } from "@/components/CardImage";

interface FlippableCardImageProps {
  frontUrl: string;
  /** The back face; null for a single-faced card, which renders with no flip button. */
  backUrl: string | null;
  flipped: boolean;
  onFlip: () => void;
  alt: string;
  width: number;
  height: number;
  foil: boolean;
  className: string;
}

/**
 * A card picture with a flip button over its corner when the card has a back face, the way
 * Scryfall and the deck builders do it. A single-faced card is just the picture.
 */
export function FlippableCardImage({
  frontUrl,
  backUrl,
  flipped,
  onFlip,
  alt,
  width,
  height,
  foil,
  className,
}: FlippableCardImageProps) {
  const showBack = flipped && backUrl !== null;

  const image = (
    <CardImage
      src={showBack ? backUrl : frontUrl}
      alt={showBack ? `${alt} (back)` : alt}
      width={width}
      height={height}
      foil={foil}
      className={className}
    />
  );

  if (backUrl === null) {
    return image;
  }

  return (
    <span className="relative block w-full">
      {image}
      <button
        type="button"
        onClick={onFlip}
        aria-pressed={showBack}
        aria-label={showBack ? "Show front face" : "Show back face"}
        className="btn btn-ghost btn-sm absolute bottom-2 right-2 rounded-full shadow-lg"
      >
        ↻ {showBack ? "Front" : "Flip"}
      </button>
    </span>
  );
}
