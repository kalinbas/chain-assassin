import { EthIcon, TrophyIcon, PlayersIcon, CalendarIcon, ClockIcon } from '../icons/Icons';
import type { Game } from '../../types/game';

export function GameStatsGrid({ game }: { game: Game }) {
  const prizePoolBps = game.bps.first + game.bps.second + game.bps.third + game.bps.kills;
  const playerCount = Math.max(game.players, game.minPlayers);
  const prizePool = (playerCount * game.entryFee * prizePoolBps / 10000).toFixed(4);
  const fillPct = Math.round((game.players / game.maxPlayers) * 100);
  const isPast = game.phase === 'ended' || game.phase === 'cancelled';

  return (
    <div className="game-detail__grid">
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><EthIcon /> Entry Fee</div>
        <div className="game-detail__stat-value game-detail__stat-value--primary">{game.entryFee} ETH</div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><TrophyIcon /> Prize Pool</div>
        <div className="game-detail__stat-value game-detail__stat-value--primary">{prizePool} ETH</div>
      </div>
      <div className="game-detail__stat game-detail__stat--wide">
        <div className="game-detail__stat-label"><PlayersIcon /> Players</div>
        <div className="game-detail__stat-value">{game.players} / {game.maxPlayers}</div>
        <div className="game-detail__players-bar">
          <div className="game-detail__players-bar-fill" style={{ width: `${fillPct}%` }} />
          {game.minPlayers > 0 && game.minPlayers < game.maxPlayers && (
            <div
              className="game-detail__players-bar-min"
              style={{ left: `${Math.round((game.minPlayers / game.maxPlayers) * 100)}%` }}
              data-tooltip={`Min ${game.minPlayers} players`}
            />
          )}
        </div>
      </div>
      {!isPast && (
        <div className="game-detail__stat">
          <div className="game-detail__stat-label"><ClockIcon /> Registration Deadline</div>
          <div className="game-detail__stat-value">{game.registrationDeadline}</div>
        </div>
      )}
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><CalendarIcon /> Game Date</div>
        <div className="game-detail__stat-value">{game.date}</div>
      </div>
    </div>
  );
}
