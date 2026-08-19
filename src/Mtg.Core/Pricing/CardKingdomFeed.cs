using System.Globalization;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using Mtg.Core.Models;

namespace Mtg.Core.Pricing;

/// <summary>
/// Card Kingdom's public price list (https://api.cardkingdom.com/api/v2/pricelist).
/// </summary>
/// <remarks>
/// This is an undocumented, unsupported feed intended for their price-syncing partners, so it
/// carries no stability guarantee. It publishes foil and nonfoil as separate rows sharing one
/// scryfall_id, which is why rows are merged rather than replaced.
/// </remarks>
public sealed class CardKingdomFeed(HttpClient httpClient, ILogger<CardKingdomFeed> logger) : IPriceFeed
{
    private const string Url = "https://api.cardkingdom.com/api/v2/pricelist";

    public string VendorId => Vendors.CardKingdom;

    public string DisplayName => "Card Kingdom";

    public string PriceBasis => "NM";

    public async Task<IReadOnlyDictionary<Guid, VendorPrice>> LoadAsync(CancellationToken cancellationToken)
    {
        var catalogue = await PriceFeedHttp.GetCatalogueAsync<Response>(
            httpClient, Url, DisplayName, cancellationToken);

        var prices = new Dictionary<Guid, VendorPrice>();

        var skipped = 0;

        foreach (var row in catalogue.Data ?? [])
        {
            if (!Guid.TryParse(row.ScryfallId, out var id) || id == Guid.Empty)
            {
                skipped++;
                continue;
            }

            var nearMint = ParsePrice(row.ConditionValues?.NearMintPrice);
            if (nearMint is null)
            {
                continue;
            }

            var isFoil = string.Equals(row.IsFoil, "true", StringComparison.OrdinalIgnoreCase);
            prices.TryGetValue(id, out var existing);

            prices[id] = isFoil
                ? new VendorPrice { Nonfoil = existing?.Nonfoil, Foil = nearMint }
                : new VendorPrice { Nonfoil = nearMint, Foil = existing?.Foil };
        }

        if (prices.Count == 0)
        {
            // Card Kingdom sometimes serves an error envelope (or a missing "data" array) with a
            // 200 status. Returning that as an empty success would replace the previous ~149,000-row
            // snapshot with nothing, so it is a failed refresh, not an empty catalogue.
            throw new PriceFeedException(
                $"The {DisplayName} price feed returned no usable prices.");
        }

        logger.LogInformation(
            "Loaded {Count} Card Kingdom prices; skipped {Skipped} rows without a usable Scryfall id.",
            prices.Count,
            skipped);

        return prices;
    }

    private static decimal? ParsePrice(string? value) =>
        decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var parsed)
        && parsed > 0
            ? parsed
            : null;

    private sealed record Response
    {
        [JsonPropertyName("data")]
        public IReadOnlyList<Row>? Data { get; init; }
    }

    private sealed record Row
    {
        /// <summary>
        /// Read as a string rather than a Guid on purpose. About 700 rows carry something that is
        /// not a GUID — null for Card Kingdom's own tokens, bare numbers like "0148", and at least
        /// one truncated GUID — and binding straight to Guid? makes a single bad row throw away the
        /// entire 149,000-row catalogue.
        /// </summary>
        [JsonPropertyName("scryfall_id")]
        public string? ScryfallId { get; init; }

        /// <summary>Quoted as the string "true" or "false", not a JSON boolean.</summary>
        [JsonPropertyName("is_foil")]
        public string? IsFoil { get; init; }

        [JsonPropertyName("condition_values")]
        public Conditions? ConditionValues { get; init; }
    }

    private sealed record Conditions
    {
        [JsonPropertyName("nm_price")]
        public string? NearMintPrice { get; init; }
    }
}
