using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace Mtg.Api.Sessions;

/// <summary>
/// The live game sessions, in memory only — a session is one evening at one table, so losing
/// them on restart costs a re-share, never data. Codes avoid 0/O, 1/I/L lookalikes because
/// guests read them off a screen across a table.
/// </summary>
internal sealed class GameSessionRegistry
{
    private const string CodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private const int CodeLength = 6;

    private readonly ConcurrentDictionary<string, GameSession> _sessions = new(StringComparer.OrdinalIgnoreCase);

    public GameSession Create()
    {
        // 31^6 codes against a handful of live tables: a collision is a retry, not a risk.
        while (true)
        {
            var session = new GameSession
            {
                Code = RandomCode(),
                HostKey = Guid.NewGuid().ToString("N"),
            };
            if (_sessions.TryAdd(session.Code, session))
            {
                return session;
            }
        }
    }

    public GameSession? Find(string code) =>
        _sessions.TryGetValue(code, out var session) ? session : null;

    public void Remove(GameSession session) =>
        _sessions.TryRemove(session.Code, out _);

    public IReadOnlyList<GameSession> Snapshot() => [.. _sessions.Values];

    private static string RandomCode() =>
        RandomNumberGenerator.GetString(CodeAlphabet, CodeLength);
}
