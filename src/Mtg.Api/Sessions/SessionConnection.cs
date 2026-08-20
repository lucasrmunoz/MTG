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
    public async Task<bool> TrySendTextAsync(string message)
    {
        using var timeout = new CancellationTokenSource(SendTimeout);
        try
        {
            await _sendLock.WaitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            return false;
        }

        try
        {
            if (Socket.State != WebSocketState.Open)
            {
                return false;
            }
            var bytes = Encoding.UTF8.GetBytes(message);
            await Socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, timeout.Token);
            return true;
        }
        catch (Exception ex) when (
            ex is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
            return false;
        }
        finally
        {
            _sendLock.Release();
        }
    }
}
