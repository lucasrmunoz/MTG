namespace Mtg.Core.Models;

/// <summary>
/// The cards whose name contains a search term, and how many matched in total.
/// </summary>
/// <remarks>
/// <see cref="TotalMatches"/> can exceed <c>Cards.Count</c>: a short term like "a" matches tens of
/// thousands of cards and only the first page is returned. Both numbers are carried so the UI can
/// say how much it is not showing rather than silently truncating.
/// </remarks>
public sealed record CardSearchResult
{
    /// <summary>Matching cards, best match first. One entry per card, not per printing.</summary>
    public required IReadOnlyList<Card> Cards { get; init; }

    /// <summary>How many cards matched the term overall, before the page limit.</summary>
    public required int TotalMatches { get; init; }

    /// <summary>An empty result, for a term that matched nothing.</summary>
    public static CardSearchResult Empty { get; } = new() { Cards = [], TotalMatches = 0 };
}
