using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;
using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>
/// Live prices from Mana Pool's public API (https://manapool.com/api/v1/products/singles).
/// </summary>
/// <remarks>
/// Mana Pool accepts a list of Scryfall ids, so prices are fetched per request and are current to
/// the second. No authentication is required.
/// </remarks>
public sealed partial class ManaPoolLiveSource(HttpClient httpClient, ILogger<ManaPoolLiveSource> logger)
    : ILivePriceSource
{
    private const string Path = "https://manapool.com/api/v1/products/singles";

    /// <summary>Mana Pool caps scryfall_ids at 100 per call, so larger sets are chunked.</summary>
    private const int MaxIdsPerRequest = 100;

    public string VendorId => Vendors.ManaPool;

    public string DisplayName => "Mana Pool";

    public string PriceBasis => "NM";

    public async Task<IReadOnlyDictionary<Guid, VendorPrice>> GetPricesAsync(
        IReadOnlyCollection<Guid> printingIds,
        CancellationToken cancellationToken)
    {
        if (printingIds.Count == 0)
        {
            return new Dictionary<Guid, VendorPrice>();
        }

        var prices = new Dictionary<Guid, VendorPrice>();
        var accepted = printingIds.Distinct().Where(IsAcceptableId).ToList();

        foreach (var chunk in accepted.Chunk(MaxIdsPerRequest))
        {
            // scryfall_ids is an array parameter and must be repeated per value. Comma-joining
            // them is rejected with a 400, which would cost the whole batch.
            var query = string.Join('&', chunk.Select(id => $"scryfall_ids={id:D}"));
            var response = await PriceFeedHttp.GetCatalogueAsync<Response>(
                httpClient, $"{Path}?{query}", DisplayName, cancellationToken);

            foreach (var row in response.Data ?? [])
            {
                if (!Guid.TryParse(row.ScryfallId, out var id) || id == Guid.Empty)
                {
                    continue;
                }

                var price = new VendorPrice
                {
                    Nonfoil = ToDollars(row.NearMintCents),
                    Foil = ToDollars(row.NearMintFoilCents),
                };

                if (!price.IsEmpty)
                {
                    prices[id] = price;
                }
            }
        }

        logger.LogDebug(
            "Mana Pool returned prices for {Priced} of {Requested} printings.",
            prices.Count,
            printingIds.Count);

        return prices;
    }

    private static decimal? ToDollars(int? cents) => cents is > 0 ? cents.Value / 100m : null;

    /// <summary>
    /// Mana Pool validates each id against a UUID pattern restricted to versions 1-8. A single id
    /// outside it fails the whole request with a 400, so non-conforming ids are dropped rather than
    /// allowed to cost every other printing in the batch.
    /// </summary>
    private static bool IsAcceptableId(Guid id) => AcceptedId().IsMatch(id.ToString("D"));

    [GeneratedRegex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOptions.IgnoreCase)]
    private static partial Regex AcceptedId();

    private sealed record Response
    {
        [JsonPropertyName("data")]
        public IReadOnlyList<Row>? Data { get; init; }
    }

    private sealed record Row
    {
        [JsonPropertyName("scryfall_id")]
        public string? ScryfallId { get; init; }

        [JsonPropertyName("price_cents_nm")]
        public int? NearMintCents { get; init; }

        [JsonPropertyName("price_cents_nm_foil")]
        public int? NearMintFoilCents { get; init; }
    }
}
