"use client";

import Image from "next/image";

interface CardImageProps {
  src: string;
  alt: string;
  width: number;
  height: number;
  /** Applies the holographic sheen and a FOIL badge. */
  foil: boolean;
  className: string;
}

/**
 * A card picture, optionally dressed as a foil.
 *
 * There is no foil scan to load — Scryfall stores one image per printing whatever the finish — so
 * the foil look is a sheen layered over the same image.
 */
export function CardImage({ src, alt, width, height, foil, className }: CardImageProps) {
  const image = (
    <Image
      key={src}
      src={src}
      alt={foil ? `${alt} (foil)` : alt}
      width={width}
      height={height}
      className={className}
    />
  );

  if (!foil) {
    return image;
  }

  return (
    <span className="foil-sheen relative">
      {image}
      <span className="absolute left-1 top-1 rounded bg-background/80 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-purple-light">
        ✨ Foil
      </span>
    </span>
  );
}
