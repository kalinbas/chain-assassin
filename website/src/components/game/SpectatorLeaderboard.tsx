import { CrosshairIcon } from '../icons/Icons';
import type { SpectatorState } from '../../hooks/useSpectatorSocket';

function rankClass(rank: number): string {
  if (rank === 1) return 'spectator__lb-rank--gold';
  if (rank === 2) return 'spectator__lb-rank--silver';
  if (rank === 3) return 'spectator__lb-rank--bronze';
  return '';
}

export function SpectatorLeaderboard({ state }: { state: SpectatorState }) {
  const entries = state.leaderboard.slice(0, 10);

  return (
    <div className="spectator__leaderboard">
      <h4 className="spectator__panel-title">Leaderboard</h4>
      <div className="spectator__lb-list">
        {entries.map((entry, i) => (
          <div
            key={entry.address}
            className={`spectator__lb-row ${!entry.isAlive ? 'spectator__lb-row--dead' : ''}`}
          >
            <span className={`spectator__lb-rank ${rankClass(i + 1)}`}>{i + 1}</span>
            <span className="spectator__lb-player">Player #{entry.playerNumber}</span>
            <span className="spectator__lb-kills">
              <CrosshairIcon width={12} height={12} style={{ verticalAlign: 'middle', marginRight: 3 }} />
              {entry.kills}
            </span>
            <span className={`spectator__lb-status ${entry.isAlive ? 'spectator__lb-status--alive' : 'spectator__lb-status--dead'}`}>
              {entry.isAlive ? 'ALIVE' : 'DEAD'}
            </span>
          </div>
        ))}
        {entries.length === 0 && (
          <p className="spectator__empty">Waiting for game data...</p>
        )}
      </div>
    </div>
  );
}
