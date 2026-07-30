using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>
/// A vendor that publishes prices as a whole-catalogue download rather than per-card lookups.
/// </summary>
/// <remarks>
/// Neither Card Kingdom nor Mana Pool offers a per-card price endpoint — both publish one large
/// JSON document covering everything they currently stock. That is why prices are cached in
/// memory and refreshed on a timer instead of being fetched per request.
/// </remarks>
public interface IPriceFeed
{
    /// <summary>Vendor id, matching a constant in <see cref="Vendors"/>.</summary>
    string VendorId { get; }

    /// <summary>Vendor name for display, e.g. "Card Kingdom".</summary>
    string DisplayName { get; }

    /// <summary>What the price means, e.g. "NM" for cheapest near-mint.</summary>
    string PriceBasis { get; }

    /// <summary>
    /// Downloads the vendor's catalogue and indexes it by Scryfall printing id.
    /// </summary>
    /// <exception cref="PriceFeedException">The feed was unreachable or unparseable.</exception>
    Task<IReadOnlyDictionary<Guid, VendorPrice>> LoadAsync(CancellationToken cancellationToken);
}
