namespace Mtg.Api.Sessions;

/// <summary>
/// Sweeps dead sessions out of the registry: a host gone past its reconnect grace, or a table
/// idle so long the game is clearly over. Remaining sockets are aborted rather than closed
/// politely — their request loops end through their normal cleanup paths.
/// </summary>
internal sealed class SessionCleanupService(GameSessionRegistry registry) : BackgroundService
{
    private static readonly TimeSpan SweepInterval = TimeSpan.FromMinutes(1);

    /// <summary>How long guests keep a session alive waiting for its host to come back.</summary>
    private static readonly TimeSpan HostGoneGrace = TimeSpan.FromMinutes(10);

    /// <summary>Longer than any real game night; only a session everyone forgot reaches this.</summary>
    private static readonly TimeSpan MaxIdle = TimeSpan.FromHours(12);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(SweepInterval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            var now = DateTimeOffset.UtcNow;
            foreach (var session in registry.Snapshot())
            {
                var hostExpired = session.HostGoneAtUtc is { } goneAt && now - goneAt > HostGoneGrace;
                var idleExpired = now - session.LastActivityUtc > MaxIdle;
                if (!hostExpired && !idleExpired)
                {
                    continue;
                }

                registry.Remove(session);
                foreach (var connection in session.Drain())
                {
                    connection.Socket.Abort();
                }
            }
        }
    }
}
