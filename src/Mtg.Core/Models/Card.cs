namespace Mtg.Core.Models;

/// <summary>
/// A Magic: The Gathering card, normalised from Scryfall into the shape this app consumes.
/// </summary>
/// <remarks>
/// Multi-faced cards (transform, modal double-faced, split, adventure) carry their per-face
/// detail in <see cref="Faces"/>; single-faced cards leave it empty.
/// </remarks>
public sealed record Card
{
    /// <summary>
    /// Scryfall's id for this specific printing. Doubles as the join key against the Card Kingdom
    /// and Mana Pool price feeds, both of which publish a scryfall_id on every row.
    /// </summary>
    public required Guid Id { get; init; }

    /// <summary>Full card name. For multi-faced cards this is the combined "Front // Back" name.</summary>
    public required string Name { get; init; }

    /// <summary>Mana cost in Scryfall symbol notation, e.g. <c>{1}{U}{U}</c>. Empty for lands.</summary>
    public string ManaCost { get; init; } = "";

    /// <summary>Converted mana cost. Fractional for some un-set cards.</summary>
    public decimal ManaValue { get; init; }

    /// <summary>Full type line, e.g. <c>Legendary Creature — Human Wizard</c>.</summary>
    public string TypeLine { get; init; } = "";

    /// <summary>Oracle rules text. Empty for vanilla creatures and basic lands.</summary>
    public string OracleText { get; init; } = "";

    /// <summary>Power, as printed. Null for non-creatures; may be <c>*</c> for variable power.</summary>
    public string? Power { get; init; }

    /// <summary>Toughness, as printed. Null for non-creatures.</summary>
    public string? Toughness { get; init; }

    /// <summary>Starting loyalty. Null for non-planeswalkers.</summary>
    public string? Loyalty { get; init; }

    /// <summary>Colors of the card itself, as single-letter codes (W, U, B, R, G).</summary>
    public IReadOnlyList<string> Colors { get; init; } = [];

    /// <summary>Commander colour identity, including colours from mana symbols in rules text.</summary>
    public IReadOnlyList<string> ColorIdentity { get; init; } = [];

    /// <summary>Keyword abilities Scryfall recognises on this card, e.g. Flying, Haste.</summary>
    public IReadOnlyList<string> Keywords { get; init; } = [];

    /// <summary>Rarity of this printing: common, uncommon, rare, mythic, special or bonus.</summary>
    public string Rarity { get; init; } = "";

    /// <summary>Set code of this printing, e.g. <c>lea</c>.</summary>
    public string SetCode { get; init; } = "";

    /// <summary>Set name of this printing, e.g. <c>Limited Edition Alpha</c>.</summary>
    public string SetName { get; init; } = "";

    /// <summary>Card image at Scryfall's "normal" size, or null when this printing has no image.</summary>
    public string? ImageUrl { get; init; }

    /// <summary>Finishes this printing was produced in: nonfoil, foil and/or etched.</summary>
    public IReadOnlyList<string> Finishes { get; init; } = [];

    /// <summary>Price per vendor, keyed by the ids in <see cref="Vendors"/>. May be empty.</summary>
    public IReadOnlyDictionary<string, VendorPrice> Prices { get; init; } =
        new Dictionary<string, VendorPrice>();

    /// <summary>Individual faces, for multi-faced cards only.</summary>
    public IReadOnlyList<CardFace> Faces { get; init; } = [];
}
