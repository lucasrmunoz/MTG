namespace Mtg.Core.Models;

/// <summary>
/// Constraints a card search applies alongside the name term.
/// </summary>
/// <remarks>
/// Values are the ids the frontend sends. <see cref="Scryfall.ScryfallFilterQuery"/> knows which
/// ids are valid and what each one means in Scryfall's query language.
/// </remarks>
public sealed record CardSearchFilters
{
    /// <summary>No constraints: the name term alone decides.</summary>
    public static CardSearchFilters None { get; } = new();

    /// <summary>A card type word, lowercase, e.g. "creature" or "land". Null for any type.</summary>
    public string? Type { get; init; }

    /// <summary>Colors as WUBRG letters, e.g. "WU". Empty for any color.</summary>
    public string Colors { get; init; } = "";

    /// <summary>"contains" or "only". Only meaningful when <see cref="Colors"/> is set.</summary>
    public string ColorMode { get; init; } = "contains";

    /// <summary>Land trait ids. Only applied when <see cref="Type"/> is "land".</summary>
    public IReadOnlyList<string> LandTraits { get; init; } = [];

    /// <summary>
    /// Whether the filters narrow anything. Land traits alone do not: they are ignored unless
    /// the type is land.
    /// </summary>
    public bool IsEmpty => Type is null && Colors.Length == 0;
}
