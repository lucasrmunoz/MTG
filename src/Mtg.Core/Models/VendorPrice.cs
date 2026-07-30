using System.Text.Json.Serialization;

namespace Mtg.Core.Models;

/// <summary>
/// What one vendor charges for one printing, in USD.
/// </summary>
/// <remarks>
/// Either finish may be null: the printing might not exist in that finish, the vendor might not
/// stock it, or the vendor's feed might not cover it. Null means "no price to show", not "free".
/// </remarks>
public sealed record VendorPrice
{
    public decimal? Nonfoil { get; init; }

    public decimal? Foil { get; init; }

    /// <summary>True when this vendor has no usable price for either finish. Internal helper, not part of the API contract.</summary>
    [JsonIgnore]
    public bool IsEmpty => Nonfoil is null && Foil is null;
}
