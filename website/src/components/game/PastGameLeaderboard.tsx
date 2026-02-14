import type { Game, GameLeaderboardEntry } from '../../types/game';

function truncAddr(addr: string): string {
  if (!addr || addr.length < 12) return addr;
  return `${addr.slice(0, 6)}...${addr.slice(-4)}`;
}

function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;

  if (h > 0) return `${h}h ${m}m ${s}s`;
  return `${m}m ${s}s`;
}

function getGameStart(game: Game): number | null {
  const start = game.activity.find((ev) => ev.type === 'start')?.timestamp;
  return start ?? null;
}

function getGameEnd(game: Game): number | null {
  const end = game.activity.find((ev) => ev.type === 'end' || ev.type === 'cancel')?.timestamp;
  return end ?? null;
}

function sortByKillTimeline(entries: GameLeaderboardEntry[]): GameLeaderboardEntry[] {
  return [...entries].sort((a, b) => {
    if (a.isAlive !== b.isAlive) return a.isAlive ? -1 : 1;

    const aElim = a.eliminatedAt ?? Number.MAX_SAFE_INTEGER;
    const bElim = b.eliminatedAt ?? Number.MAX_SAFE_INTEGER;
    if (aElim !== bElim) return bElim - aElim;

    if (a.kills !== b.kills) return b.kills - a.kills;
    return a.playerNumber - b.playerNumber;
  });
}

export function PastGameLeaderboard({ game }: { game: Game }) {
  if (game.leaderboard.length === 0) return null;

  const startAt = getGameStart(game);
  const endAt = getGameEnd(game);
  const rows = sortByKillTimeline(game.leaderboard);

  return (
    <section className="past-leaderboard">
      <h3 className="game-detail__section-title">Final Leaderboard</h3>
      <p className="past-leaderboard__sub">
        Ordered by elimination timeline (latest elimination first).
      </p>

      <div className="past-leaderboard__table">
        <div className="past-leaderboard__head">
          <span>#</span>
          <span>Player</span>
          <span>Address</span>
          <span>Kills</span>
          <span>Survival</span>
          <span>Status</span>
        </div>

        {rows.map((entry, index) => {
          const survivalUntil = entry.isAlive
            ? (endAt ?? entry.eliminatedAt ?? startAt ?? 0)
            : (entry.eliminatedAt ?? startAt ?? 0);
          const survivalSeconds = startAt == null ? null : Math.max(0, survivalUntil - startAt);
          const knockoutAt = (!entry.isAlive && startAt != null && entry.eliminatedAt != null)
            ? `T+${formatDuration(entry.eliminatedAt - startAt)}`
            : null;

          return (
            <div className={`past-leaderboard__row ${entry.isAlive ? 'past-leaderboard__row--alive' : ''}`} key={entry.playerNumber}>
              <span className="past-leaderboard__rank">{index + 1}</span>
              <span className="past-leaderboard__player">#{entry.playerNumber}</span>
              <span className="past-leaderboard__addr" title={entry.address}>{truncAddr(entry.address)}</span>
              <span className="past-leaderboard__kills">{entry.kills}</span>
              <span className="past-leaderboard__survival">
                {survivalSeconds == null ? '—' : formatDuration(survivalSeconds)}
              </span>
              <span className={`past-leaderboard__status ${entry.isAlive ? 'past-leaderboard__status--alive' : 'past-leaderboard__status--dead'}`}>
                {entry.isAlive ? 'WINNER' : 'OUT'}
                {!entry.isAlive && knockoutAt && (
                  <span className="past-leaderboard__status-time">{knockoutAt}</span>
                )}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
