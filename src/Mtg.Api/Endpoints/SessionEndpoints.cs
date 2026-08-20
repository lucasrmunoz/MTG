using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Mtg.Api.Sessions;

namespace Mtg.Api.Endpoints;

/// <summary>
/// The game-session relay: one WebSocket endpoint over which a host phone shares its commander
/// game and guests follow along and send actions back. The server only routes and caches opaque
/// payloads — the game's shape and rules live entirely in the clients.
///
/// Wire protocol (JSON text frames). First message declares the role:
/// host `{role:"host"}` → `{type:"created",code,hostKey}`;
/// host `{role:"host",code,hostKey}` → `{type:"resumed",code,guestCount}`;
/// guest `{role:"guest",code}` → `{type:"joined"}` then the cached state, if any.
/// After that: host `{type:"state",payload}` caches and broadcasts; host `{type:"end"}` tears the
/// session down; guest `{type:"action",payload}` forwards to the host. The server volunteers
/// `{type:"presence",guestCount}` to the host and `{type:"host-gone"|"host-back"|"session-ended"}`
/// to guests. Failures before a role is established answer `{type:"error",reason}` and close.
/// </summary>
internal static class SessionEndpoints
{
    /// <summary>Generous ceiling for one frame; a full six-player state is a few kilobytes.</summary>
    private const int MaxMessageBytes = 256 * 1024;

    /// <summary>How long a fresh socket gets to declare its role before being dropped.</summary>
    private static readonly TimeSpan HelloTimeout = TimeSpan.FromSeconds(10);

    public static IEndpointRouteBuilder MapSessionEndpoints(this IEndpointRouteBuilder app)
    {
        app.Map("/api/sessions/ws", HandleAsync)
            .WithName("GameSessionSocket");
        return app;
    }

    private static async Task HandleAsync(HttpContext context, GameSessionRegistry registry)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await context.Response.WriteAsync("This endpoint speaks WebSocket only.");
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        var connection = new SessionConnection(socket);
        var aborted = context.RequestAborted;

        using var helloTimeout = CancellationTokenSource.CreateLinkedTokenSource(aborted);
        helloTimeout.CancelAfter(HelloTimeout);
        var hello = await ReceiveTextAsync(socket, helloTimeout.Token);
        if (hello is null)
        {
            return;
        }

        string? role, code, hostKey;
        using (var parsed = TryParse(hello))
        {
            if (parsed is null)
            {
                await RefuseAsync(connection, "bad-request");
                return;
            }
            role = GetString(parsed.RootElement, "role");
            code = GetString(parsed.RootElement, "code");
            hostKey = GetString(parsed.RootElement, "hostKey");
        }

        switch (role)
        {
            case "host" when code is null:
                await RunHostAsync(registry, registry.Create(), connection, isResume: false, aborted);
                break;

            case "host":
                var resuming = registry.Find(code);
                if (resuming is null || resuming.HostKey != hostKey)
                {
                    await RefuseAsync(connection, "not-found");
                    return;
                }
                await RunHostAsync(registry, resuming, connection, isResume: true, aborted);
                break;

            case "guest" when code is not null:
                var joining = registry.Find(code);
                if (joining is null)
                {
                    await RefuseAsync(connection, "not-found");
                    return;
                }
                await RunGuestAsync(joining, connection, aborted);
                break;

            default:
                await RefuseAsync(connection, "bad-request");
                break;
        }
    }

    private static async Task RunHostAsync(
        GameSessionRegistry registry,
        GameSession session,
        SessionConnection connection,
        bool isResume,
        CancellationToken aborted)
    {
        // A resume replaces a connection the host phone believes is dead; if it somehow is not,
        // aborting it keeps exactly one host loop attached.
        session.AttachHost(connection)?.Socket.Abort();

        var greeting = isResume
            ? JsonSerializer.Serialize(new { type = "resumed", code = session.Code, guestCount = session.GuestCount })
            : JsonSerializer.Serialize(new { type = "created", code = session.Code, hostKey = session.HostKey });
        if (!await connection.TrySendTextAsync(greeting))
        {
            DetachHost(session, connection);
            return;
        }

        if (isResume)
        {
            await BroadcastAsync(session, """{"type":"host-back"}""");
        }

        try
        {
            while (true)
            {
                var message = await ReceiveTextAsync(connection.Socket, aborted);
                if (message is null || !await HandleHostMessageAsync(registry, session, message))
                {
                    return;
                }
            }
        }
        finally
        {
            DetachHost(session, connection);
        }
    }

    /// <summary>Handles one host frame; false ends the host loop because the session is over.</summary>
    private static async Task<bool> HandleHostMessageAsync(
        GameSessionRegistry registry,
        GameSession session,
        string message)
    {
        using var parsed = TryParse(message);
        if (parsed is null)
        {
            return true; // One malformed frame is ignored, not a reason to drop the table.
        }

        var root = parsed.RootElement;
        switch (GetString(root, "type"))
        {
            case "state" when root.TryGetProperty("payload", out var payload):
                // Re-serialised while the document is alive; the payload itself is never inspected.
                var outbound = JsonSerializer.Serialize(new { type = "state", payload });
                session.SetCachedState(outbound);
                await BroadcastAsync(session, outbound);
                return true;

            case "end":
                registry.Remove(session);
                await BroadcastAsync(session, """{"type":"session-ended"}""");
                foreach (var guest in session.Drain())
                {
                    guest.Socket.Abort();
                }
                return false;

            default:
                return true;
        }
    }

    private static async Task RunGuestAsync(
        GameSession session,
        SessionConnection connection,
        CancellationToken aborted)
    {
        var (guestCount, cachedState, hostPresent) = session.AddGuest(connection);
        try
        {
            if (!await connection.TrySendTextAsync("""{"type":"joined"}"""))
            {
                return;
            }
            if (cachedState is not null)
            {
                await connection.TrySendTextAsync(cachedState);
            }
            if (!hostPresent)
            {
                await connection.TrySendTextAsync("""{"type":"host-gone"}""");
            }
            await NotifyHostAsync(session, PresenceMessage(guestCount));

            while (true)
            {
                var message = await ReceiveTextAsync(connection.Socket, aborted);
                if (message is null)
                {
                    return;
                }
                using var parsed = TryParse(message);
                if (parsed is null)
                {
                    continue;
                }
                var root = parsed.RootElement;
                if (GetString(root, "type") == "action" && root.TryGetProperty("payload", out var payload))
                {
                    await NotifyHostAsync(session, JsonSerializer.Serialize(new { type = "action", payload }));
                }
            }
        }
        finally
        {
            var remaining = session.RemoveGuest(connection);
            await NotifyHostAsync(session, PresenceMessage(remaining));
        }
    }

    private static void DetachHost(GameSession session, SessionConnection connection)
    {
        if (session.TryDetachHost(connection))
        {
            // Fire-and-forget is deliberate: the host's request is ending and must not wait on
            // guest sockets; each send guards itself and failures only prune dead guests.
            _ = BroadcastAsync(session, """{"type":"host-gone"}""");
        }
    }

    private static async Task BroadcastAsync(GameSession session, string message)
    {
        foreach (var guest in session.GuestsSnapshot())
        {
            if (!await guest.TrySendTextAsync(message))
            {
                session.RemoveGuest(guest);
            }
        }
    }

    private static async Task NotifyHostAsync(GameSession session, string message)
    {
        var host = session.Host;
        if (host is not null)
        {
            await host.TrySendTextAsync(message);
        }
    }

    private static string PresenceMessage(int guestCount) =>
        JsonSerializer.Serialize(new { type = "presence", guestCount });

    private static async Task RefuseAsync(SessionConnection connection, string reason)
    {
        await connection.TrySendTextAsync(JsonSerializer.Serialize(new { type = "error", reason }));
        await CloseAsync(connection.Socket, WebSocketCloseStatus.PolicyViolation, reason);
    }

    /// <summary>One whole text message, or null when the peer closed, overflowed, or vanished.</summary>
    private static async Task<string?> ReceiveTextAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[8 * 1024];
        using var message = new MemoryStream();
        try
        {
            while (true)
            {
                var result = await socket.ReceiveAsync(buffer, cancellationToken);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    return null;
                }
                message.Write(buffer, 0, result.Count);
                if (message.Length > MaxMessageBytes)
                {
                    await CloseAsync(socket, WebSocketCloseStatus.MessageTooBig, "Message too large.");
                    return null;
                }
                if (!result.EndOfMessage)
                {
                    continue;
                }
                if (result.MessageType != WebSocketMessageType.Text)
                {
                    message.SetLength(0); // Binary frames are not part of the protocol; skipped.
                    continue;
                }
                return Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
            }
        }
        catch (Exception ex) when (
            ex is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            return null;
        }
    }

    private static async Task CloseAsync(WebSocket socket, WebSocketCloseStatus status, string description)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            await socket.CloseAsync(status, description, timeout.Token);
        }
        catch (Exception ex) when (
            ex is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            // The peer is already gone; there is nobody left to close politely for.
        }
    }

    private static JsonDocument? TryParse(string text)
    {
        try
        {
            return JsonDocument.Parse(text);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string? GetString(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object &&
        element.TryGetProperty(property, out var value) &&
        value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
}
