namespace Mtg.Core.Pricing;

/// <summary>
/// Raised when a vendor's price feed could not be downloaded or parsed. A failed refresh is never
/// fatal — the previous snapshot stays in use and the next refresh tries again.
/// </summary>
public sealed class PriceFeedException(string message, Exception? innerException = null)
    : Exception(message, innerException);
