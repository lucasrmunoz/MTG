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
    /// Finds every card whose name contains the search term.
    /// </summary>
    /// <remarks>
    /// Each whitespace-separated word must appear somewhere in the name, in any order: "bolt light"
    /// and "light bolt" both find Lightning Bolt. Scryfall's <c>name:</c> operator matches inside
    /// words too, so "olt" finds Aether Revolt. Only the first page of results is returned —
    /// <see cref="CardSearchResult.TotalMatches"/> reports how many there were altogether.
    /// <para>
    /// A term that no name contains falls back to Scryfall's fuzzy lookup, which tolerates
    /// misspellings a substring match cannot.
    /// </para>
    /// </remarks>
    /// <param name="searchTerm">Whole or partial card name, e.g. "lightning bolt" or "bolt".</param>
    /// <param name="cancellationToken">Cancels the outbound request.</param>
    /// <returns>Matching cards, closest match first; empty when nothing matches.</returns>
    /// <exception cref="ScryfallException">Scryfall was unreachable or returned an unusable response.</exception>
    public async Task<CardSearchResult> SearchCardsByNameAsync(
        string searchTerm,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(searchTerm);

        var nameQuery = BuildNameQuery(searchTerm);
        if (nameQuery is null)
        {
            return CardSearchResult.Empty;
        }

        var url = $"cards/search?q={Uri.EscapeDataString(nameQuery)}&unique=cards&order=name&dir=asc";

        // A 404 here means no card name contains the term, which is an empty page, not a failure.
        var result = await GetAsync<ScryfallList>(url, $"card search for '{searchTerm}'", cancellationToken);
        if (result?.Data is not { Count: > 0 } matches)
        {
            return await FindByFuzzyNameAsync(searchTerm, cancellationToken);
        }

        var cards = matches
            .Select(ScryfallCardMapper.ToCard)
            .OrderBy(card => MatchRank(card.Name, searchTerm.Trim()))
            .ThenBy(card => card.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        return new CardSearchResult { Cards = cards, TotalMatches = result.TotalCards };
    }

    /// <summary>
    /// Scryfall's fuzzy name lookup, which tolerates misspellings — "snapcastr mage" finds
    /// Snapcaster Mage — where a substring match finds nothing at all.
    /// </summary>
    /// <remarks>
    /// Only reached when the substring search came back empty, so it costs a second request on the
    /// rare path rather than on every search. Scryfall answers 404 both when nothing resembles the
    /// term and when too many cards do; either way there is no single card to offer.
    /// </remarks>
    private async Task<CardSearchResult> FindByFuzzyNameAsync(
        string searchTerm,
        CancellationToken cancellationToken)
    {
        await Task.Delay(PageDelay, cancellationToken);

        var url = $"cards/named?fuzzy={Uri.EscapeDataString(searchTerm)}";
        var card = await GetAsync<ScryfallCard>(url, $"fuzzy card lookup for '{searchTerm}'", cancellationToken);

        return card is null
            ? CardSearchResult.Empty
            : new CardSearchResult { Cards = [ScryfallCardMapper.ToCard(card)], TotalMatches = 1 };
    }

    /// <summary>
    /// Turns a search term into a Scryfall query of ANDed <c>name:</c> clauses, one per word.
    /// </summary>
    /// <remarks>
    /// Each word is quoted so that punctuation carries through — <c>name:"Urza's"</c> works, and a
    /// leading hyphen is read as part of the name rather than as Scryfall's negation operator.
    /// Quotes and backslashes are stripped instead of escaped, because Scryfall's query parser has
    /// no escape sequence for them inside a quoted string.
    /// </remarks>
    /// <returns>The query, or null when the term holds nothing searchable.</returns>
    private static string? BuildNameQuery(string searchTerm)
    {
        var clauses = searchTerm
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(word => word.Replace("\"", "", StringComparison.Ordinal)
                                .Replace("\\", "", StringComparison.Ordinal))
            .Where(word => word.Length > 0)
            .Select(word => $"name:\"{word}\"")
            .ToList();

        return clauses.Count == 0 ? null : string.Join(' ', clauses);
    }

    /// <summary>
    /// Orders matches by how closely the whole name tracks the term, so that searching the full
    /// name of a card puts that card first instead of alphabetically among its partial matches.
    /// </summary>
    private static int MatchRank(string cardName, string searchTerm)
    {
        if (cardName.Equals(searchTerm, StringComparison.OrdinalIgnoreCase))
        {
            return 0;
        }

        return cardName.StartsWith(searchTerm, StringComparison.OrdinalIgnoreCase) ? 1 : 2;
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
