using Microsoft.AspNetCore.Diagnostics;
using Mtg.Core.Scryfall;

namespace Mtg.Api;

/// <summary>
/// Turns a Scryfall outage into an explicit 502, so callers can tell "the upstream card database
/// is unavailable" from "that card does not exist".
/// </summary>
internal sealed class ScryfallExceptionHandler(
    ILogger<ScryfallExceptionHandler> logger,
    IProblemDetailsService problemDetailsService) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(
        HttpContext httpContext,
        Exception exception,
        CancellationToken cancellationToken)
    {
        if (exception is not ScryfallException)
        {
            return false;
        }

        logger.LogError(exception, "Scryfall request failed for {Path}", httpContext.Request.Path);

        httpContext.Response.StatusCode = StatusCodes.Status502BadGateway;

        return await problemDetailsService.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = httpContext,
            Exception = exception,
            ProblemDetails =
            {
                Title = "Card data provider unavailable",
                Detail = exception.Message,
                Status = StatusCodes.Status502BadGateway,
            },
        });
    }
}
