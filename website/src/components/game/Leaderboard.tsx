import type { Game } from '../../types/game';
import { truncAddr } from '../../lib/contract';

const ZERO = '0x0000000000000000000000000000000000000000';

interface LeaderboardEntry {
  rank: string;
  label: string;
  address: string;
  prize: string;
  modifier: string;
}

export function Leaderboard({ game }: { game: Game }) {
  const playerCount = Math.max(game.players, game.minPlayers);
  const totalPool = playerCount * game.entryFee;

  const entries: LeaderboardEntry[] = [];

  if (game.winner1 !== ZERO) {
    entries.push({
      rank: '1st',
      label: '1st Place',
      address: game.winner1,
      prize: (totalPool * game.bps.first / 10000).toFixed(4),
      modifier: '1st',
    });
  }

  if (game.winner2 !== ZERO) {
    entries.push({
      rank: '2nd',
      label: '2nd Place',
      address: game.winner2,
      prize: (totalPool * game.bps.second / 10000).toFixed(4),
      modifier: '2nd',
    });
  }

  if (game.winner3 !== ZERO) {
    entries.push({
      rank: '3rd',
      label: '3rd Place',
      address: game.winner3,
      prize: (totalPool * game.bps.third / 10000).toFixed(4),
      modifier: '3rd',
    });
  }

  if (game.topKiller !== ZERO) {
    entries.push({
      rank: '\u2694',
      label: 'Top Killer',
      address: game.topKiller,
      prize: (totalPool * game.bps.kills / 10000).toFixed(4),
      modifier: 'kills',
    });
  }

  if (entries.length === 0) return null;

  return (
    <div className="leaderboard">
      <h3 className="game-detail__section-title">Leaderboard</h3>
      <div className="leaderboard__list">
        {entries.map((entry) => (
          <div className={`leaderboard__row leaderboard__row--${entry.modifier}`} key={entry.modifier}>
            <span className="leaderboard__rank">{entry.rank}</span>
            <div className="leaderboard__info">
              <span className="leaderboard__label">{entry.label}</span>
              <span className="leaderboard__address">{truncAddr(entry.address)}</span>
            </div>
            <span className="leaderboard__prize">{entry.prize} ETH</span>
          </div>
        ))}
      </div>
    </div>
  );
}
