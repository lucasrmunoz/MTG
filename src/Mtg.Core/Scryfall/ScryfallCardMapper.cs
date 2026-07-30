using System.Globalization;
using Mtg.Core.Models;

namespace Mtg.Core.Scryfall;

/// <summary>
/// Translates Scryfall's wire format into this app's models.
/// </summary>
/// <remarks>
/// Only the TCGplayer price is available here, because Scryfall carries it inline on the card.
/// Card Kingdom and Mana Pool prices arrive later, from the bulk feeds, via the price cache.
/// </remarks>
internal static class ScryfallCardMapper
{
    public static Card ToCard(ScryfallCard source) => new()
    {
        Id = source.Id,
        Name = source.Name ?? "",
        ManaCost = source.ManaCost ?? "",
        ManaValue = source.Cmc,
        TypeLine = source.TypeLine ?? "",
        OracleText = source.OracleText ?? "",
        Power = source.Power,
        Toughness = source.Toughness,
        Loyalty = source.Loyalty,
        Colors = source.Colors ?? [],
        ColorIdentity = source.ColorIdentity ?? [],
        Keywords = source.Keywords ?? [],
        Rarity = source.Rarity ?? "",
        SetCode = source.Set ?? "",
        SetName = source.SetName ?? "",
        ImageUrl = ResolveImageUrl(source),
        Finishes = source.Finishes ?? [],
        Prices = TcgplayerPrice(source),
        Faces = source.CardFaces?.Select(ToFace).ToList() ?? [],
    };

    /// <summary>
    /// Maps a printing to an art version. Returns null when the printing has no usable scan,
    /// since the only reason to surface an art version is to show its picture.
    /// </summary>
    public static ArtVersion? ToArtVersion(ScryfallCard source)
    {
        var imageUrl = ResolveImageUrl(source);
        if (imageUrl is null)
        {
            return null;
        }

        return new ArtVersion
        {
            Id = source.Id,
            SetCode = source.Set ?? "",
            SetName = source.SetName ?? "Unknown Set",
            CollectorNumber = source.CollectorNumber ?? "",
            Artist = source.Artist ?? "Unknown Artist",
            ReleasedAt = source.ReleasedAt,
            ImageUrl = imageUrl,
            ArtCropUrl = ResolveArtCropUrl(source),
            Finishes = source.Finishes ?? [],
            Prices = TcgplayerPrice(source),
        };
    }

    private static IReadOnlyDictionary<string, VendorPrice> TcgplayerPrice(ScryfallCard source)
    {
        var price = new VendorPrice
        {
            Nonfoil = ParsePrice(source.Prices?.Usd),
            Foil = ParsePrice(source.Prices?.UsdFoil),
        };

        return price.IsEmpty
            ? new Dictionary<string, VendorPrice>()
            : new Dictionary<string, VendorPrice> { [Vendors.Tcgplayer] = price };
    }

    /// <summary>Scryfall quotes prices as decimal strings, and omits them entirely when unknown.</summary>
    private static decimal? ParsePrice(string? value) =>
        decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : null;

    private static CardFace ToFace(ScryfallCardFace face) => new()
    {
        Name = face.Name ?? "",
        ManaCost = face.ManaCost ?? "",
        TypeLine = face.TypeLine ?? "",
        OracleText = face.OracleText ?? "",
        Power = face.Power,
        Toughness = face.Toughness,
        ImageUrl = face.ImageUris?.Normal,
    };

    /// <summary>
    /// Transform and modal double-faced cards carry no top-level image_uris — their scans hang off
    /// each entry in card_faces. Falling back to the front face is what makes cards like
    /// Delver of Secrets show a picture at all.
    /// </summary>
    private static string? ResolveImageUrl(ScryfallCard source) =>
        source.ImageUris?.Normal ?? FirstFaceImages(source)?.Normal;

    private static string? ResolveArtCropUrl(ScryfallCard source) =>
        source.ImageUris?.ArtCrop ?? FirstFaceImages(source)?.ArtCrop;

    private static ScryfallImageUris? FirstFaceImages(ScryfallCard source) =>
        source.CardFaces?.FirstOrDefault(face => face.ImageUris is not null)?.ImageUris;
}
