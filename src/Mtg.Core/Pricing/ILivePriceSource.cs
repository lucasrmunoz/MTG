using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>
/// A vendor that can be asked for specific printings on demand, so its prices are fetched fresh on
/// every request rather than served from a snapshot.
/// </summary>
/// <remarks>
/// Preferred over <see cref="IPriceFeed"/> wherever the vendor supports it. Only vendors with no
/// per-card endpoint fall back to a cached bulk catalogue.
/// </remarks>
public interface ILivePriceSource
{
    /// <summary>Vendor id, matching a constant in <see cref="Vendors"/>.</summary>
    string VendorId { get; }

    /// <summary>Vendor name for display, e.g. "Mana Pool".</summary>
    string DisplayName { get; }

    /// <summary>What the price means, e.g. "NM" for cheapest near-mint.</summary>
    string PriceBasis { get; }

    /// <summary>
    /// Current prices for the given printings. Printings the vendor does not stock are simply
    /// absent from the result.
    /// </summary>
    Task<IReadOnlyDictionary<Guid, VendorPrice>> GetPricesAsync(
        IReadOnlyCollection<Guid> printingIds,
        CancellationToken cancellationToken);
}
