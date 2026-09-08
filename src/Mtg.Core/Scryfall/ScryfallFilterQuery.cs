using System.Collections.Frozen;
using Mtg.Core.Models;

namespace Mtg.Core.Scryfall;

/// <summary>
/// Turns <see cref="CardSearchFilters"/> into Scryfall query clauses, and says which filter
/// values exist. Mirrors the frontend's <c>lib/search.ts</c>.
/// </summary>
public static class ScryfallFilterQuery
{
    private const string LandType = "land";

    /// <summary>Card types a search can be narrowed to — the words Scryfall's <c>t:</c> takes.</summary>
    private static readonly FrozenSet<string> CardTypes = FrozenSet.ToFrozenSet(
    [
        "artifact",
        "battle",
        "creature",
        "enchantment",
        "instant",
        "kindred",
        LandType,
        "planeswalker",
        "sorcery",
    ]);

    /// <summary>Land trait id to its Scryfall clause. Every selected trait must match.</summary>
    private static readonly FrozenDictionary<string, string> LandTraits = new Dictionary<string, string>
    {
        ["basic"] = "t:basic",
        ["legendary"] = "t:legendary",
        ["nonbasic"] = "-t:basic",
        ["snow"] = "t:snow",
    }.ToFrozenDictionary();

    /// <summary>Land subtype id to its Scryfall clause. A land matches when it has any selected one.</summary>
    private static readonly FrozenDictionary<string, string> LandTypes = new Dictionary<string, string>
    {
        ["cave"] = "t:cave",
        ["desert"] = "t:desert",
        ["forest"] = "t:forest",
        ["gate"] = "t:gate",
        ["island"] = "t:island",
        ["lair"] = "t:lair",
        ["locus"] = "t:locus",
        ["mountain"] = "t:mountain",
        ["plains"] = "t:plains",
        ["planet"] = "t:planet",
        ["sphere"] = "t:sphere",
        ["swamp"] = "t:swamp",
        ["town"] = "t:town",
    }.ToFrozenDictionary();

    private static readonly FrozenSet<string> ColorModes = FrozenSet.ToFrozenSet(["contains", "only"]);

    private const string ColorLetters = "WUBRG";

    /// <summary>
    /// Checks every filter value against the known ids.
    /// </summary>
    /// <returns>Null when all values are valid; otherwise a message naming the offending one.</returns>
    public static string? Validate(CardSearchFilters filters)
    {
        ArgumentNullException.ThrowIfNull(filters);

        if (filters.Type is not null && !CardTypes.Contains(filters.Type))
        {
            return $"Unknown card type '{filters.Type}'. Expected one of: {string.Join(", ", CardTypes)}.";
        }

        var badColor = filters.Colors.FirstOrDefault(letter => !ColorLetters.Contains(char.ToUpperInvariant(letter)));
        if (badColor != default)
        {
            return $"Unknown color '{badColor}'. Expected letters from {ColorLetters}.";
        }

        if (!ColorModes.Contains(filters.ColorMode))
        {
            return $"Unknown color mode '{filters.ColorMode}'. Expected one of: {string.Join(", ", ColorModes)}.";
        }

        var badTrait = filters.LandTraits.FirstOrDefault(
            trait => !LandTraits.ContainsKey(trait) && !LandTypes.ContainsKey(trait));
        if (badTrait is not null)
        {
            return $"Unknown land trait '{badTrait}'. Expected one of: "
                + $"{string.Join(", ", LandTraits.Keys.Concat(LandTypes.Keys).Order())}.";
        }

        return null;
    }

    /// <summary>
    /// The Scryfall clauses for the filters, to be ANDed onto the name clauses.
    /// </summary>
    /// <remarks>
    /// Colors use <c>color&gt;=</c> ("contains") or <c>color&lt;=</c> ("only") — except for
    /// lands, which are colorless as printed: there the filter goes by color identity
    /// (<c>id</c>) instead, so "blue lands" finds Island and Breeding Pool rather than nothing.
    /// Land traits apply only when the type is land: each trait is ANDed, while the land types are
    /// ORed together in one parenthesised clause, so Gate + Town lists every Gate and every Town.
    /// </remarks>
    /// <exception cref="ArgumentException">A filter value is unknown; see <see cref="Validate"/>.</exception>
    public static IReadOnlyList<string> Build(CardSearchFilters filters)
    {
        var invalid = Validate(filters);
        if (invalid is not null)
        {
            throw new ArgumentException(invalid, nameof(filters));
        }

        var clauses = new List<string>();

        if (filters.Type is not null)
        {
            clauses.Add($"t:{filters.Type}");
        }

        if (filters.Colors.Length > 0)
        {
            var field = filters.Type == LandType ? "id" : "color";
            var op = filters.ColorMode == "only" ? "<=" : ">=";
            clauses.Add($"{field}{op}{filters.Colors.ToLowerInvariant()}");
        }

        if (filters.Type == LandType)
        {
            clauses.AddRange(filters.LandTraits.Where(LandTraits.ContainsKey).Select(trait => LandTraits[trait]));

            var types = filters.LandTraits.Where(LandTypes.ContainsKey).Select(type => LandTypes[type]).ToList();
            if (types.Count > 0)
            {
                clauses.Add($"({string.Join(" or ", types)})");
            }
        }

        return clauses;
    }
}
