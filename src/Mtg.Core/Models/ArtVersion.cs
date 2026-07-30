namespace Mtg.Core.Models;

/// <summary>
/// One printing of a card with distinct artwork. A card printed in five sets with the same
/// art yields one <see cref="ArtVersion"/>; a card with five different arts yields five.
/// </summary>
public sealed record ArtVersion
{
    /// <summary>Scryfall's id for this printing, and the join key for the vendor price feeds.</summary>
    public required Guid Id { get; init; }

    /// <summary>Set code of this printing, e.g. <c>2xm</c>.</summary>
    public required string SetCode { get; init; }

    /// <summary>Set name of this printing, e.g. <c>Double Masters</c>.</summary>
    public required string SetName { get; init; }

    /// <summary>Collector number within the set, e.g. <c>129</c> or <c>329★</c>.</summary>
    public required string CollectorNumber { get; init; }

    /// <summary>Credited illustrator.</summary>
    public string Artist { get; init; } = "";

    /// <summary>Release date of the set, as an ISO <c>yyyy-MM-dd</c> string.</summary>
    public string? ReleasedAt { get; init; }

    /// <summary>Full card image at Scryfall's "normal" size.</summary>
    public required string ImageUrl { get; init; }

    /// <summary>Cropped artwork without the card frame, suitable for thumbnails.</summary>
    public string? ArtCropUrl { get; init; }

    /// <summary>Finishes this printing was produced in: nonfoil, foil and/or etched.</summary>
    public IReadOnlyList<string> Finishes { get; init; } = [];

    /// <summary>Price per vendor, keyed by the ids in <see cref="Vendors"/>. May be empty.</summary>
    public IReadOnlyDictionary<string, VendorPrice> Prices { get; init; } =
        new Dictionary<string, VendorPrice>();
}
