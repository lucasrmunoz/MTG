namespace Mtg.Api.Sessions;

/// <summary>
/// One table's relay session: the host phone that owns the game plus the guests following it.
///
/// The server never reads the game itself — the host's latest state rides through as an opaque
/// payload, cached only so a newly joined guest sees the board before the host's next change.
/// Every game rule lives in the host's client, so a session is just membership plus that cache.
/// </summary>
internal sealed class GameSession
{
    private readonly Lock _lock = new();
    private readonly List<SessionConnection> _guests = [];
    private SessionConnection? _host;

    /// <summary>Short join code the guests type or scan; unique among live sessions.</summary>
    public required string Code { get; init; }

    /// <summary>Secret the host presents to re-attach after a dropped connection.</summary>
    public required string HostKey { get; init; }

    /// <summary>Latest full state message, exactly as broadcast; null before the first publish.</summary>
    public string? CachedStateMessage { get; private set; }

    public DateTimeOffset LastActivityUtc { get; private set; } = DateTimeOffset.UtcNow;

    /// <summary>When the host's socket dropped; null while a host is attached.</summary>
    public DateTimeOffset? HostGoneAtUtc { get; private set; }

    public SessionConnection? Host
    {
        get
        {
            lock (_lock)
            {
                return _host;
            }
        }
    }

    public int GuestCount
    {
        get
        {
            lock (_lock)
            {
                return _guests.Count;
            }
        }
    }

    /// <summary>Attaches the host, returning any previous host socket so the caller can kill it.</summary>
    public SessionConnection? AttachHost(SessionConnection connection)
    {
        lock (_lock)
        {
            var previous = _host;
            _host = connection;
            HostGoneAtUtc = null;
            LastActivityUtc = DateTimeOffset.UtcNow;
            return previous;
        }
    }

    /// <summary>
    /// Detaches the host, but only if it still is the host — a resumed connection must not be
    /// detached by the zombie it replaced closing down. True when the session lost its host.
    /// </summary>
    public bool TryDetachHost(SessionConnection connection)
    {
        lock (_lock)
        {
            if (!ReferenceEquals(_host, connection))
            {
                return false;
            }
            _host = null;
            HostGoneAtUtc = DateTimeOffset.UtcNow;
            return true;
        }
    }

    /// <summary>Adds a guest and snapshots what its welcome needs, atomically with membership.</summary>
    public (int GuestCount, string? CachedState, bool HostPresent) AddGuest(SessionConnection connection)
    {
        lock (_lock)
        {
            _guests.Add(connection);
            LastActivityUtc = DateTimeOffset.UtcNow;
            return (_guests.Count, CachedStateMessage, _host is not null);
        }
    }

    /// <summary>Removes a guest if present — safe to call twice — and returns the remaining count.</summary>
    public int RemoveGuest(SessionConnection connection)
    {
        lock (_lock)
        {
            _guests.Remove(connection);
            return _guests.Count;
        }
    }

    public IReadOnlyList<SessionConnection> GuestsSnapshot()
    {
        lock (_lock)
        {
            return [.. _guests];
        }
    }

    public void SetCachedState(string message)
    {
        lock (_lock)
        {
            CachedStateMessage = message;
            LastActivityUtc = DateTimeOffset.UtcNow;
        }
    }

    /// <summary>Empties the session for teardown, returning every connection so the caller can close them.</summary>
    public IReadOnlyList<SessionConnection> Drain()
    {
        lock (_lock)
        {
            List<SessionConnection> connections = [.. _guests];
            if (_host is not null)
            {
                connections.Add(_host);
            }
            _guests.Clear();
            _host = null;
            return connections;
        }
    }
}
