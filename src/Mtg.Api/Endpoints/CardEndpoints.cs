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
            .WithName("SearchCard")
            .WithSummary("Finds one card by name, tolerating misspellings.");

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
    // than throwing during parameter binding and surfacing as a 500.
    private static async Task<Results<Ok<Card>, ProblemHttpResult>> SearchAsync(
        string? name,
        ScryfallClient scryfall,
        CardPricingService pricing,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            return MissingName();
        }

        var card = await scryfall.FindCardByNameAsync(name, cancellationToken);

        if (card is null)
        {
            return TypedResults.Problem(
                detail: $"No card matches '{name}'. Check the spelling and try again.",
                statusCode: StatusCodes.Status404NotFound,
                title: "Card not found");
        }

        return TypedResults.Ok(await pricing.EnrichAsync(card, cancellationToken));
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

    private static ProblemHttpResult MissingName() => TypedResults.Problem(
        detail: "The 'name' query parameter is required.",
        statusCode: StatusCodes.Status400BadRequest,
        title: "Invalid request");
}
