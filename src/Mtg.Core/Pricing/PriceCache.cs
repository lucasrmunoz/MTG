using System.Collections.Concurrent;
using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>One vendor's catalogue, with the time it was downloaded.</summary>
public sealed record PriceSnapshot
{
    public required IReadOnlyDictionary<Guid, VendorPrice> Prices { get; init; }

    /// <summary>
    /// When this app downloaded the catalogue, in UTC. Reported to clients so a cached vendor's
    /// staleness is visible rather than implied.
    /// </summary>
    public required DateTimeOffset FetchedAt { get; init; }
}

/// <summary>
/// In-memory snapshot of the bulk vendor feeds, indexed by Scryfall printing id.
/// </summary>
/// <remarks>
/// Only vendors without a per-card endpoint land here — currently just Card Kingdom. Written only
/// by <see cref="PriceRefreshService"/>, and each vendor's snapshot is swapped in whole, so a
/// reader sees either the previous catalogue or the new one and never a half-written mix.
/// </remarks>
public sealed class PriceCache
{
    private readonly ConcurrentDictionary<string, PriceSnapshot> _byVendor = new();

    public void Set(string vendorId, IReadOnlyDictionary<Guid, VendorPrice> prices) =>
        _byVendor[vendorId] = new PriceSnapshot
        {
            Prices = prices,
            FetchedAt = DateTimeOffset.UtcNow,
        };

    /// <summary>True once this vendor's first download has completed.</summary>
    public bool IsLoaded(string vendorId) => _byVendor.ContainsKey(vendorId);

    /// <summary>When this vendor's catalogue was downloaded, or null if it never has been.</summary>
    public DateTimeOffset? FetchedAt(string vendorId) =>
        _byVendor.TryGetValue(vendorId, out var snapshot) ? snapshot.FetchedAt : null;

    /// <summary>
    /// Every cached vendor price for one printing. Vendors that do not stock it are absent rather
    /// than present with nulls, so the caller can tell "not stocked" from "not loaded yet".
    /// </summary>
    public IReadOnlyDictionary<string, VendorPrice> PricesFor(Guid printingId)
    {
        var result = new Dictionary<string, VendorPrice>();

        foreach (var (vendorId, snapshot) in _byVendor)
        {
            if (snapshot.Prices.TryGetValue(printingId, out var price) && !price.IsEmpty)
            {
                result[vendorId] = price;
            }
        }

        return result;
    }
}
