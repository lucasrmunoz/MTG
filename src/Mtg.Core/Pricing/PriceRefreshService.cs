using System.Diagnostics;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Mtg.Core.Pricing;

/// <summary>
/// Keeps <see cref="PriceCache"/> current by re-downloading every bulk vendor feed on a timer.
/// </summary>
/// <remarks>
/// Runs in the background rather than on first request because the catalogues are large — Card
/// Kingdom is about 66 MB and Mana Pool about 50 MB — and no card lookup should wait on them.
/// Until the first download lands, cards simply carry no price for that vendor.
/// </remarks>
public sealed class PriceRefreshService(
    IEnumerable<IPriceFeed> feeds,
    PriceCache cache,
    ILogger<PriceRefreshService> logger) : BackgroundService
{
    /// <summary>
    /// Card Kingdom regenerates its price list periodically rather than continuously, so hourly
    /// keeps the cache close to the source without hammering a 66 MB download.
    /// </summary>
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromHours(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(RefreshInterval);

        try
        {
            do
            {
                await Task.WhenAll(feeds.Select(feed => RefreshAsync(feed, stoppingToken)));
            }
            while (await timer.WaitForNextTickAsync(stoppingToken));
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
    }

    private async Task RefreshAsync(IPriceFeed feed, CancellationToken cancellationToken)
    {
        var stopwatch = Stopwatch.StartNew();

        try
        {
            var prices = await feed.LoadAsync(cancellationToken);
            cache.Set(feed.VendorId, prices);

            logger.LogInformation(
                "{Vendor} prices refreshed: {Count} printings in {Seconds:F1}s.",
                feed.DisplayName,
                prices.Count,
                stopwatch.Elapsed.TotalSeconds);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            // A vendor being down must not kill the refresh loop or the other vendors — the app is
            // fully usable without this feed, and the previous snapshot stays in place.
            logger.LogError(
                ex,
                "{Vendor} price refresh failed after {Seconds:F1}s; keeping the previous snapshot.",
                feed.DisplayName,
                stopwatch.Elapsed.TotalSeconds);
        }
    }
}
