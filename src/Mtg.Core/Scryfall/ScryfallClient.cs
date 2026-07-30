using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Mtg.Core.Models;

namespace Mtg.Core.Scryfall;

/// <summary>
/// Reads card data from the Scryfall API (https://scryfall.com/docs/api).
/// </summary>
/// <remarks>
/// Register with <see cref="ServiceCollectionExtensions.AddScryfall"/>, which supplies the base
/// address and the identifying headers Scryfall requires of API clients.
/// </remarks>
public sealed class ScryfallClient(HttpClient httpClient, ILogger<ScryfallClient> logger)
{
    /// <summary>Scryfall asks for 50-100ms between requests from a single client.</summary>
    private static readonly TimeSpan PageDelay = TimeSpan.FromMilliseconds(100);

    /// <summary>
    /// Safety bound on pagination. Scryfall returns 175 cards per page and no card has anywhere
    /// near 1750 distinct arts, so hitting this means something is wrong, not that a card is popular.
    /// </summary>
    private const int MaxArtPages = 10;

    /// <summary>
    /// Finds a single card by name, tolerating misspellings and partial names.
    /// </summary>
    /// <param name="cardName">Card name to search for, e.g. "lightning bolt" or "snapcastr".</param>
    /// <param name="cancellationToken">Cancels the outbound request.</param>
    /// <returns>The matching card, or null if Scryfall knows no card by that name.</returns>
    /// <exception cref="ScryfallException">Scryfall was unreachable or returned an unusable response.</exception>
    public async Task<Card?> FindCardByNameAsync(string cardName, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(cardName);

        var url = $"cards/named?fuzzy={Uri.EscapeDataString(cardName)}";
        var card = await GetAsync<ScryfallCard>(url, $"card lookup for '{cardName}'", cancellationToken);

        return card is null ? null : ScryfallCardMapper.ToCard(card);
    }

    /// <summary>
    /// Lists every printing of a card that uses distinct artwork, oldest first.
    /// </summary>
    /// <param name="cardName">Exact card name. Unlike name lookup, this does not fuzzy match.</param>
    /// <param name="cancellationToken">Cancels the outbound requests.</param>
    /// <returns>Art versions with a usable image; empty if the name matches nothing.</returns>
    /// <exception cref="ScryfallException">Scryfall was unreachable or returned an unusable response.</exception>
    public async Task<IReadOnlyList<ArtVersion>> GetArtVersionsAsync(
        string cardName,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(cardName);

        var operation = $"art lookup for '{cardName}'";
        var query = Uri.EscapeDataString($"!\"{cardName}\"");
        string? url = $"cards/search?q={query}&unique=art&order=released&dir=asc";

        var versions = new List<ArtVersion>();
        var page = 0;

        while (url is not null)
        {
            if (page == MaxArtPages)
            {
                logger.LogWarning(
                    "Stopped after {MaxArtPages} pages of art for {CardName}; Scryfall has more results.",
                    MaxArtPages,
                    cardName);
                break;
            }

            if (page > 0)
            {
                await Task.Delay(PageDelay, cancellationToken);
            }

            page++;

            // A 404 here means the exact name matched no printings, which is an empty result.
            var result = await GetAsync<ScryfallList>(url, operation, cancellationToken);
            if (result?.Data is null)
            {
                break;
            }

            versions.AddRange(result.Data.Select(ScryfallCardMapper.ToArtVersion).OfType<ArtVersion>());
            url = result.HasMore ? result.NextPage : null;
        }

        return versions;
    }

    /// <summary>
    /// Issues a GET and deserialises the body, translating every failure mode except
    /// "not found" into a <see cref="ScryfallException"/>.
    /// </summary>
    /// <returns>The deserialised body, or null when Scryfall answered 404.</returns>
    private async Task<T?> GetAsync<T>(string url, string operation, CancellationToken cancellationToken)
        where T : class
    {
        HttpResponseMessage response;
        try
        {
            response = await httpClient.GetAsync(url, cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            throw new ScryfallException(
                $"Could not reach Scryfall during {operation}. Check network connectivity.", ex);
        }
        catch (TaskCanceledException ex) when (!cancellationToken.IsCancellationRequested)
        {
            throw new ScryfallException($"Scryfall timed out during {operation}.", ex);
        }

        using (response)
        {
            if (response.StatusCode == HttpStatusCode.NotFound)
            {
                return null;
            }

            if (!response.IsSuccessStatusCode)
            {
                throw new ScryfallException(
                    $"Scryfall returned {(int)response.StatusCode} {response.ReasonPhrase} during {operation}.");
            }

            try
            {
                return await response.Content.ReadFromJsonAsync<T>(cancellationToken);
            }
            catch (JsonException ex)
            {
                throw new ScryfallException($"Could not parse the Scryfall response for {operation}.", ex);
            }
        }
    }
}
