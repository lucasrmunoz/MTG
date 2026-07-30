namespace Mtg.Core.Scryfall;

/// <summary>
/// Raised when Scryfall could not be reached, refused the request, or returned something
/// unparseable. A card that simply does not exist is not an error — that surfaces as a null
/// result from <see cref="ScryfallClient.FindCardByNameAsync"/>.
/// </summary>
public sealed class ScryfallException(string message, Exception? innerException = null)
    : Exception(message, innerException);
