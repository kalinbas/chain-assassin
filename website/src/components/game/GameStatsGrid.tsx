import { EthIcon, TrophyIcon, PlayersIcon, UserIcon, PinIcon, CalendarIcon, ClockIcon, PersonIcon } from '../icons/Icons';
import { truncAddr } from '../../lib/contract';
import type { Game } from '../../types/game';

export function GameStatsGrid({ game }: { game: Game }) {
  const prizePoolBps = game.bps.first + game.bps.second + game.bps.third + game.bps.kills;
  const prizePool = (game.players * game.entryFee * prizePoolBps / 10000).toFixed(4);
  const fillPct = Math.round((game.players / game.maxPlayers) * 100);

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
        </div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><UserIcon /> Min Players</div>
        <div className="game-detail__stat-value">{game.minPlayers}</div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><PinIcon /> Location</div>
        <div className="game-detail__stat-value">{game.location}</div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><CalendarIcon /> Game Date</div>
        <div className="game-detail__stat-value">{game.date}</div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><ClockIcon /> Registration Deadline</div>
        <div className="game-detail__stat-value">{game.registrationDeadline}</div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><ClockIcon /> Expiry Deadline</div>
        <div className="game-detail__stat-value">{game.expiryDeadline}</div>
      </div>
      <div className="game-detail__stat">
        <div className="game-detail__stat-label"><PersonIcon /> Creator</div>
        <div className="game-detail__stat-value">{truncAddr(game.creator)}</div>
      </div>
    </div>
  );
}
