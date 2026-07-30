namespace Mtg.Core.Models;

/// <summary>
/// One face of a multi-faced card. Only transform and modal double-faced cards carry
/// their own image per face; split and adventure cards share the parent card's image.
/// </summary>
public sealed record CardFace
{
    /// <summary>Name of this face alone.</summary>
    public required string Name { get; init; }

    /// <summary>Mana cost of this face. Empty for the back face of a transforming card.</summary>
    public string ManaCost { get; init; } = "";

    /// <summary>Type line of this face.</summary>
    public string TypeLine { get; init; } = "";

    /// <summary>Oracle rules text of this face.</summary>
    public string OracleText { get; init; } = "";

    /// <summary>Power of this face, as printed. Null for non-creature faces.</summary>
    public string? Power { get; init; }

    /// <summary>Toughness of this face, as printed. Null for non-creature faces.</summary>
    public string? Toughness { get; init; }

    /// <summary>Image of this face, or null when the faces share the parent card's image.</summary>
    public string? ImageUrl { get; init; }
}
