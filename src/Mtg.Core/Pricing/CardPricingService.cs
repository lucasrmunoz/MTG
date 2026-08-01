using Microsoft.Extensions.Logging;
using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>
/// Attaches vendor prices to cards and art versions.
/// </summary>
/// <remarks>
/// Prices come from three places, in increasing order of staleness:
/// TCGplayer arrives inline on the Scryfall response; Mana Pool is queried live per request;
/// Card Kingdom is read from the cached catalogue, because it publishes no per-card endpoint.
/// </remarks>
public sealed class CardPricingService(
    PriceCache cache,
    IEnumerable<ILivePriceSource> liveSources,
    ILogger<CardPricingService> logger)
{
    public async Task<IReadOnlyList<Card>> EnrichAsync(
        IReadOnlyList<Card> cards,
        CancellationToken cancellationToken)
    {
        if (cards.Count == 0)
        {
            return cards;
        }

        var live = await FetchLiveAsync([.. cards.Select(card => card.Id)], cancellationToken);

        return cards
            .Select(card => card with { Prices = Combine(card.Prices, card.Id, live) })
            .ToList();
    }

    public async Task<IReadOnlyList<ArtVersion>> EnrichAsync(
        IReadOnlyList<ArtVersion> versions,
        CancellationToken cancellationToken)
    {
        if (versions.Count == 0)
        {
            return versions;
        }

        var live = await FetchLiveAsync([.. versions.Select(version => version.Id)], cancellationToken);

        return versions
            .Select(version => version with { Prices = Combine(version.Prices, version.Id, live) })
            .ToList();
    }

    /// <summary>
    /// Queries every live vendor for the given printings, in parallel.
    /// </summary>
    /// <remarks>
    /// A vendor being down must not fail the card lookup, so failures are logged and that vendor's
    /// prices are simply omitted.
    /// </remarks>
    private async Task<IReadOnlyDictionary<string, IReadOnlyDictionary<Guid, VendorPrice>>> FetchLiveAsync(
        IReadOnlyCollection<Guid> printingIds,
        CancellationToken cancellationToken)
    {
        var results = await Task.WhenAll(liveSources.Select(async source =>
        {
            try
            {
                var prices = await source.GetPricesAsync(printingIds, cancellationToken);
                return (source.VendorId, Prices: prices);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                logger.LogWarning(
                    ex,
                    "Live price lookup failed for {Vendor}; continuing without its prices.",
                    source.DisplayName);

                return (source.VendorId, Prices: (IReadOnlyDictionary<Guid, VendorPrice>)
                    new Dictionary<Guid, VendorPrice>());
            }
        }));

        return results.ToDictionary(result => result.VendorId, result => result.Prices);
    }

    /// <summary>Merges the inline Scryfall price with cached and live vendor prices.</summary>
    private IReadOnlyDictionary<string, VendorPrice> Combine(
        IReadOnlyDictionary<string, VendorPrice> inline,
        Guid printingId,
        IReadOnlyDictionary<string, IReadOnlyDictionary<Guid, VendorPrice>> live)
    {
        var merged = new Dictionary<string, VendorPrice>(inline);

        foreach (var (vendorId, price) in cache.PricesFor(printingId))
        {
            merged[vendorId] = price;
        }

        foreach (var (vendorId, prices) in live)
        {
            if (prices.TryGetValue(printingId, out var price) && !price.IsEmpty)
            {
                merged[vendorId] = price;
            }
        }

        return merged;
    }
}
