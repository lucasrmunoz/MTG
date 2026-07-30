using System.Net.Http.Json;
using System.Text.Json;

namespace Mtg.Core.Pricing;

/// <summary>
/// Shared download and error-translation policy for the bulk price feeds.
/// </summary>
internal static class PriceFeedHttp
{
    /// <summary>
    /// Streams and deserialises a vendor catalogue, turning every failure into
    /// <see cref="PriceFeedException"/> so the refresh loop has one thing to catch.
    /// </summary>
    /// <remarks>
    /// The feed DTOs deliberately declare only the handful of fields that are used. System.Text.Json
    /// skips everything else without allocating it, which keeps a 66 MB document from turning into
    /// hundreds of megabytes of objects.
    /// </remarks>
    public static async Task<T> GetCatalogueAsync<T>(
        HttpClient httpClient,
        string url,
        string vendorName,
        CancellationToken cancellationToken)
        where T : class
    {
        HttpResponseMessage response;
        try
        {
            response = await httpClient.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            throw new PriceFeedException($"Could not reach the {vendorName} price feed at {url}.", ex);
        }
        catch (TaskCanceledException ex) when (!cancellationToken.IsCancellationRequested)
        {
            throw new PriceFeedException($"The {vendorName} price feed timed out.", ex);
        }

        using (response)
        {
            if (!response.IsSuccessStatusCode)
            {
                throw new PriceFeedException(
                    $"The {vendorName} price feed returned {(int)response.StatusCode} {response.ReasonPhrase}.");
            }

            try
            {
                return await response.Content.ReadFromJsonAsync<T>(cancellationToken)
                       ?? throw new PriceFeedException($"The {vendorName} price feed returned an empty body.");
            }
            catch (JsonException ex)
            {
                throw new PriceFeedException($"Could not parse the {vendorName} price feed.", ex);
            }
        }
    }
}
