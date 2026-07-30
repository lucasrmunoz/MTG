using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>
/// The vendor list the UI builds its dropdowns from, in display order.
/// </summary>
/// <remarks>
/// Live vendors come first, since their prices are current to the second. TCGplayer is neither a
/// feed nor a live source: Scryfall carries its price inline on every card, so it needs no lookup
/// of its own. It is listed last because its number is a market price rather than the cheapest
/// near-mint copy the others report.
/// </remarks>
public sealed class VendorCatalog(
    IEnumerable<ILivePriceSource> liveSources,
    IEnumerable<IPriceFeed> feeds,
    PriceCache cache)
{
    public IReadOnlyList<VendorInfo> List()
    {
        var vendors = liveSources
            .Select(source => new VendorInfo
            {
                Id = source.VendorId,
                Name = source.DisplayName,
                PriceBasis = source.PriceBasis,
                Live = true,
                Loaded = true,
            })
            .ToList();

        vendors.AddRange(feeds.Select(feed => new VendorInfo
        {
            Id = feed.VendorId,
            Name = feed.DisplayName,
            PriceBasis = feed.PriceBasis,
            Live = false,
            FetchedAt = cache.FetchedAt(feed.VendorId),
            Loaded = cache.IsLoaded(feed.VendorId),
        }));

        vendors.Add(new VendorInfo
        {
            Id = Vendors.Tcgplayer,
            Name = "TCGplayer",
            PriceBasis = "market",
            Live = true,
            Loaded = true,
        });

        return vendors;
    }
}
