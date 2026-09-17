using System.Net.WebSockets;
using System.Text;

namespace Mtg.Api.Sessions;

/// <summary>
/// One live socket with its sends serialised — a session broadcasts to the same socket from
/// several request loops, and WebSocket allows only one send at a time.
/// </summary>
internal sealed class SessionConnection(WebSocket socket)
{
    /// <summary>Bounds a send to a hung peer so one dead phone cannot stall a whole table.</summary>
    private static readonly TimeSpan SendTimeout = TimeSpan.FromSeconds(10);

    private readonly SemaphoreSlim _sendLock = new(1, 1);

    public WebSocket Socket { get; } = socket;

    /// <summary>
    /// Sends one text frame. False when the socket is closed or the send fails — a dead peer is
    /// an expected outcome the caller prunes, never an exception that tears down another loop.
    /// </summary>
    public Task<bool> TrySendTextAsync(string message) =>
        TrySendTextAsync(() => [message]);

    /// <summary>
    /// Sends the frames <paramref name="compose"/> produces, holding this socket's send lock from
    /// before it runs until the last frame is out. Anything <paramref name="compose"/> does that
    /// makes this socket a broadcast target — joining a session — therefore cannot let a
    /// concurrent broadcast land ahead of, or between, the frames it composed. False on the
    /// first frame that fails, as for a single send.
    /// </summary>
    public async Task<bool> TrySendTextAsync(Func<IReadOnlyList<string>> compose)
    {
        using var lockTimeout = new CancellationTokenSource(SendTimeout);
        try
        {
            await _sendLock.WaitAsync(lockTimeout.Token);
        }
        catch (OperationCanceledException)
        {
            return false;
        }

        try
        {
            foreach (var message in compose())
            {
                if (!await SendLockedAsync(Encoding.UTF8.GetBytes(message), WebSocketMessageType.Text))
                {
                    return false;
                }
            }
            return true;
        }
        finally
        {
            _sendLock.Release();
        }
    }

    /// <summary>One frame, with the send lock already held by the caller.</summary>
    private async Task<bool> SendLockedAsync(byte[] payload, WebSocketMessageType messageType)
    {
        if (Socket.State != WebSocketState.Open)
        {
            return false;
        }
        using var timeout = new CancellationTokenSource(SendTimeout);
        try
        {
            await Socket.SendAsync(payload, messageType, endOfMessage: true, timeout.Token);
            return true;
        }
        catch (Exception ex) when (
            ex is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
            return false;
        }
    }
}
