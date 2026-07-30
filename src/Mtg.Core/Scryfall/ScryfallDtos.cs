using System.Text.Json.Serialization;

namespace Mtg.Core.Scryfall;

/// <summary>
/// A Scryfall card object. Fields are optional because Scryfall omits what does not apply:
/// non-creatures have no power, single-faced cards have no card_faces, and so on.
/// </summary>
/// <remarks>See https://scryfall.com/docs/api/cards for the full object.</remarks>
internal sealed record ScryfallCard
{
    [JsonPropertyName("id")]
    public Guid Id { get; init; }

    [JsonPropertyName("name")]
    public string? Name { get; init; }

    [JsonPropertyName("mana_cost")]
    public string? ManaCost { get; init; }

    [JsonPropertyName("cmc")]
    public decimal Cmc { get; init; }

    [JsonPropertyName("type_line")]
    public string? TypeLine { get; init; }

    [JsonPropertyName("oracle_text")]
    public string? OracleText { get; init; }

    [JsonPropertyName("power")]
    public string? Power { get; init; }

    [JsonPropertyName("toughness")]
    public string? Toughness { get; init; }

    [JsonPropertyName("loyalty")]
    public string? Loyalty { get; init; }

    [JsonPropertyName("colors")]
    public IReadOnlyList<string>? Colors { get; init; }

    [JsonPropertyName("color_identity")]
    public IReadOnlyList<string>? ColorIdentity { get; init; }

    [JsonPropertyName("keywords")]
    public IReadOnlyList<string>? Keywords { get; init; }

    [JsonPropertyName("rarity")]
    public string? Rarity { get; init; }

    [JsonPropertyName("set")]
    public string? Set { get; init; }

    [JsonPropertyName("set_name")]
    public string? SetName { get; init; }

    [JsonPropertyName("collector_number")]
    public string? CollectorNumber { get; init; }

    [JsonPropertyName("artist")]
    public string? Artist { get; init; }

    [JsonPropertyName("released_at")]
    public string? ReleasedAt { get; init; }

    [JsonPropertyName("finishes")]
    public IReadOnlyList<string>? Finishes { get; init; }

    [JsonPropertyName("prices")]
    public ScryfallPrices? Prices { get; init; }

    [JsonPropertyName("image_uris")]
    public ScryfallImageUris? ImageUris { get; init; }

    [JsonPropertyName("card_faces")]
    public IReadOnlyList<ScryfallCardFace>? CardFaces { get; init; }
}

/// <summary>
/// Scryfall's price block. Sourced from TCGplayer for USD, and quoted as decimal strings.
/// There is no condition dimension here — this is a single market figure per finish.
/// </summary>
internal sealed record ScryfallPrices
{
    [JsonPropertyName("usd")]
    public string? Usd { get; init; }

    [JsonPropertyName("usd_foil")]
    public string? UsdFoil { get; init; }
}

/// <summary>One face of a multi-faced Scryfall card.</summary>
internal sealed record ScryfallCardFace
{
    [JsonPropertyName("name")]
    public string? Name { get; init; }

    [JsonPropertyName("mana_cost")]
    public string? ManaCost { get; init; }

    [JsonPropertyName("type_line")]
    public string? TypeLine { get; init; }

    [JsonPropertyName("oracle_text")]
    public string? OracleText { get; init; }

    [JsonPropertyName("power")]
    public string? Power { get; init; }

    [JsonPropertyName("toughness")]
    public string? Toughness { get; init; }

    [JsonPropertyName("image_uris")]
    public ScryfallImageUris? ImageUris { get; init; }
}

/// <summary>Scryfall's per-card image sizes. Absent entirely on cards with no scan.</summary>
internal sealed record ScryfallImageUris
{
    [JsonPropertyName("small")]
    public string? Small { get; init; }

    [JsonPropertyName("normal")]
    public string? Normal { get; init; }

    [JsonPropertyName("large")]
    public string? Large { get; init; }

    [JsonPropertyName("art_crop")]
    public string? ArtCrop { get; init; }
}

/// <summary>A paginated Scryfall list object.</summary>
internal sealed record ScryfallList
{
    [JsonPropertyName("total_cards")]
    public int TotalCards { get; init; }

    [JsonPropertyName("has_more")]
    public bool HasMore { get; init; }

    [JsonPropertyName("next_page")]
    public string? NextPage { get; init; }

    [JsonPropertyName("data")]
    public IReadOnlyList<ScryfallCard>? Data { get; init; }
}
