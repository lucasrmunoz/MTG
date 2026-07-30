namespace Mtg.Core.Models;

/// <summary>
/// The price vendors the app knows about, and what their numbers actually mean.
/// </summary>
/// <remarks>
/// The vendors do not agree on what a "price" is, and pretending otherwise would be misleading.
/// Card Kingdom and Mana Pool both publish per-condition prices, so those are the cheapest
/// near-mint copy. Scryfall exposes a single TCGplayer figure with no condition breakdown, so
/// TCGplayer is a market price — hence <see cref="VendorInfo.PriceBasis"/>, which the UI shows
/// next to the vendor name.
/// </remarks>
public static class Vendors
{
    public const string CardKingdom = "cardKingdom";
    public const string ManaPool = "manaPool";
    public const string Tcgplayer = "tcgplayer";
}

/// <summary>A price vendor as advertised to clients.</summary>
public sealed record VendorInfo
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    /// <summary>What the number means, e.g. "NM" or "market". Shown beside the vendor name.</summary>
    public required string PriceBasis { get; init; }

    /// <summary>
    /// True when prices are fetched fresh on every request. False means they come from a cached
    /// catalogue, because the vendor publishes no per-card endpoint.
    /// </summary>
    public required bool Live { get; init; }

    /// <summary>
    /// For cached vendors, when this app last downloaded the catalogue, in UTC. Null for live
    /// vendors, and null for a cached vendor whose first download has not finished.
    /// </summary>
    public DateTimeOffset? FetchedAt { get; init; }

    /// <summary>
    /// False only while a cached vendor's first download is still running. Lets the UI say
    /// "loading prices" instead of showing dashes that look like "this card is worthless".
    /// </summary>
    public required bool Loaded { get; init; }
}
