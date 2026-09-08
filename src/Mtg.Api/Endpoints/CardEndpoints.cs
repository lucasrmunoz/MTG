using Microsoft.AspNetCore.Http.HttpResults;
using Mtg.Core.Models;
using Mtg.Core.Pricing;
using Mtg.Core.Scryfall;

namespace Mtg.Api.Endpoints;

/// <summary>
/// Card lookup endpoints, backed by Scryfall and the cached vendor price feeds.
/// </summary>
internal static class CardEndpoints
{
    public static IEndpointRouteBuilder MapCardEndpoints(this IEndpointRouteBuilder app)
    {
        var cards = app.MapGroup("/api/cards").WithTags("Cards");

        cards.MapGet("/search", SearchAsync)
            .WithName("SearchCards")
            .WithSummary("Finds every card whose name contains the search term and passes the filters.");

        cards.MapGet("/art", GetArtVersionsAsync)
            .WithName("GetCardArt")
            .WithSummary("Lists every printing of a card that uses distinct artwork, with prices.");

        app.MapGet("/api/vendors", ListVendors)
            .WithName("ListVendors")
            .WithTags("Prices")
            .WithSummary("Lists price vendors, what their prices mean, and whether each feed has loaded.");

        return app;
    }

    // 'name' is nullable so that omitting it entirely fails validation here with a 400, rather
    // than throwing during parameter binding and surfacing as a 500. It may be blank when a
    // filter is set — "every Planet land" needs no name.
    // A term that matches no card is an empty result rather than a 404: this is a list endpoint,
    // and "nothing contains that text" is an answer, not a missing resource.
    private static async Task<Results<Ok<CardSearchResult>, ProblemHttpResult>> SearchAsync(
        string? name,
        string? type,
        string? colors,
        string? colorMode,
        string[]? landTraits,
        ScryfallClient scryfall,
        CardPricingService pricing,
        CancellationToken cancellationToken)
    {
        var filters = new CardSearchFilters
        {
            Type = type,
            Colors = colors ?? "",
            ColorMode = colorMode ?? CardSearchFilters.None.ColorMode,
            LandTraits = landTraits ?? [],
        };

        var invalid = ScryfallFilterQuery.Validate(filters);
        if (invalid is not null)
        {
            return InvalidRequest(invalid);
        }

        if (string.IsNullOrWhiteSpace(name) && filters.IsEmpty)
        {
            return InvalidRequest(
                "Provide the 'name' query parameter, or at least one of 'type' and 'colors'.");
        }

        var result = await scryfall.SearchCardsAsync(name ?? "", filters, cancellationToken);

        return TypedResults.Ok(result with
        {
            Cards = await pricing.EnrichAsync(result.Cards, cancellationToken),
        });
    }

    private static async Task<Results<Ok<IReadOnlyList<ArtVersion>>, ProblemHttpResult>> GetArtVersionsAsync(
        string? name,
        ScryfallClient scryfall,
        CardPricingService pricing,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            return MissingName();
        }

        var versions = await scryfall.GetArtVersionsAsync(name, cancellationToken);

        return TypedResults.Ok(await pricing.EnrichAsync(versions, cancellationToken));
    }

    private static Ok<IReadOnlyList<VendorInfo>> ListVendors(VendorCatalog catalog) =>
        TypedResults.Ok(catalog.List());

    private static ProblemHttpResult MissingName() =>
        InvalidRequest("The 'name' query parameter is required.");

    private static ProblemHttpResult InvalidRequest(string detail) => TypedResults.Problem(
        detail: detail,
        statusCode: StatusCodes.Status400BadRequest,
        title: "Invalid request");
}
