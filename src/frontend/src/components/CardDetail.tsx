"use client";

import { CardImage } from "@/components/CardImage";
import { KeywordChip } from "@/components/KeywordChip";
import { powerToughness, primaryType } from "@/lib/cards";
import { formatPrice, priceFor, vendorLabel, type Finish } from "@/lib/pricing";
import type { Card, VendorInfo } from "@/lib/types";

interface CardDetailProps {
  card: Card;
  /** The art the user picked, falling back to the card's default printing. */
  imageUrl: string | null;
  vendors: VendorInfo[];
  vendorId: string;
  finish: Finish;
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <span className="text-orange/90 font-semibold text-xs uppercase tracking-wider">
        {label}
      </span>
      <div className="text-foreground mt-1">{children}</div>
    </div>
  );
}

export function CardDetail({
  card,
  imageUrl,
  vendors,
  vendorId,
  finish,
}: CardDetailProps) {
  const pt = powerToughness(card);
  const showFoil = finish === "foil";
  const vendor = vendors.find((candidate) => candidate.id === vendorId);
  const price = priceFor(card.prices, vendorId, finish);

  return (
    <div className="flex flex-col md:flex-row gap-6 md:gap-8">
      <div className="w-full max-w-[300px] flex-shrink-0">
        {imageUrl === null ? (
          <div className="aspect-[300/418] w-full bg-background/50 rounded-lg flex items-center justify-center border border-foreground/10">
            <span className="text-foreground/40 text-sm">No image available</span>
          </div>
        ) : (
          <CardImage
            src={imageUrl}
            alt={card.name}
            width={300}
            height={418}
            foil={showFoil}
            className="rounded-lg w-full h-auto shadow-[0_12px_32px_-12px_rgba(0,0,0,0.7)]"
          />
        )}
      </div>

      <div className="flex-1 space-y-4">
        <h3 className="font-display text-2xl font-bold text-purple-light">{card.name}</h3>

        <div className="space-y-3">
          <DetailRow label="Type">{card.typeLine}</DetailRow>

          <DetailRow label="Mana Cost">
            {card.manaCost === "" ? "—" : card.manaCost}
          </DetailRow>

          {pt !== null && <DetailRow label="Power / Toughness">{pt}</DetailRow>}

          {card.loyalty !== null && (
            <DetailRow label="Loyalty">{card.loyalty}</DetailRow>
          )}

          {card.keywords.length > 0 && (
            <DetailRow label="Keywords">
              <span className="flex flex-wrap gap-2">
                {card.keywords.map((keyword) => (
                  <KeywordChip key={keyword} keyword={keyword} />
                ))}
              </span>
            </DetailRow>
          )}

          {card.faces.length > 0 ? (
            <DetailRow label="Card Text">
              {card.faces.map((face) => (
                <div key={face.name} className="mb-3 last:mb-0">
                  <p className="text-purple-light font-semibold">
                    {face.name}
                    <span className="text-foreground/50 font-normal">
                      {" "}
                      — {primaryType(face.typeLine)}
                    </span>
                  </p>
                  <p className="whitespace-pre-line">{face.oracleText}</p>
                </div>
              ))}
            </DetailRow>
          ) : (
            <DetailRow label="Card Text">
              <span className="whitespace-pre-line">
                {card.oracleText === "" ? "—" : card.oracleText}
              </span>
            </DetailRow>
          )}

          <DetailRow label="Printing">
            {card.setName} ({card.setCode.toUpperCase()}) · {card.rarity}
          </DetailRow>

          <DetailRow label="Price">
            <span className="text-xl font-bold text-orange">{formatPrice(price)}</span>
            {vendor !== undefined && (
              <span className="text-foreground/50 text-sm ml-2">
                {vendorLabel(vendor)}
                {showFoil ? " · foil" : ""}
              </span>
            )}
          </DetailRow>
        </div>
      </div>
    </div>
  );
}
