using System.Net.Http.Headers;
using Microsoft.Extensions.DependencyInjection;
using Mtg.Core.Pricing;
using Mtg.Core.Scryfall;

namespace Mtg.Core;

/// <summary>
/// Dependency injection wiring for the Core library.
/// </summary>
public static class ServiceCollectionExtensions
{
    /// <summary>Scryfall asks every client to identify itself; requests without this may be refused.</summary>
    private const string UserAgent = "MtgDeckBuilder/1.0";

    private static readonly Uri ScryfallBaseAddress = new("https://api.scryfall.com/");

    private static readonly TimeSpan ScryfallTimeout = TimeSpan.FromSeconds(15);

    /// <summary>Bulk catalogues run to tens of megabytes, so they need far longer than a card lookup.</summary>
    private static readonly TimeSpan PriceFeedTimeout = TimeSpan.FromMinutes(10);

    /// <summary>
    /// Registers <see cref="ScryfallClient"/> as a typed HTTP client.
    /// </summary>
    public static IServiceCollection AddScryfall(this IServiceCollection services)
    {
        services.AddHttpClient<ScryfallClient>(client =>
        {
            client.BaseAddress = ScryfallBaseAddress;
            client.Timeout = ScryfallTimeout;
            client.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
            client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        });

        return services;
    }

    /// <summary>
    /// Registers the vendor price sources.
    /// </summary>
    /// <remarks>
    /// Mana Pool is queried live per request. Card Kingdom has no per-card endpoint, so it is the
    /// only vendor served from a cached catalogue and the only reason the refresh loop exists.
    /// </remarks>
    public static IServiceCollection AddPricing(this IServiceCollection services)
    {
        // PriceCache is a singleton because the refresh loop fills it once for the whole app.
        // The other two are scoped: both reach the live sources, which are typed HTTP clients and
        // must not be captured by a singleton.
        services.AddSingleton<PriceCache>();
        services.AddScoped<VendorCatalog>();
        services.AddScoped<CardPricingService>();

        AddLiveSource<ManaPoolLiveSource>(services);
        AddFeed<CardKingdomFeed>(services);

        services.AddHostedService<PriceRefreshService>();

        return services;
    }

    /// <summary>Registers a live vendor as a typed HTTP client and an <see cref="ILivePriceSource"/>.</summary>
    private static void AddLiveSource<TSource>(IServiceCollection services)
        where TSource : class, ILivePriceSource
    {
        services.AddHttpClient<TSource>(ConfigureVendorClient);
        services.AddScoped<ILivePriceSource>(provider => provider.GetRequiredService<TSource>());
    }

    /// <summary>
    /// Registers a feed as both a typed HTTP client and an <see cref="IPriceFeed"/>, so the refresh
    /// service can enumerate every vendor without knowing the concrete types.
    /// </summary>
    private static void AddFeed<TFeed>(IServiceCollection services)
        where TFeed : class, IPriceFeed
    {
        services.AddHttpClient<TFeed>(ConfigureVendorClient);
        services.AddSingleton<IPriceFeed>(provider => provider.GetRequiredService<TFeed>());
    }

    private static void ConfigureVendorClient(HttpClient client)
    {
        client.Timeout = PriceFeedTimeout;
        client.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
    }
}
