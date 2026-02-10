import { Link } from 'react-router-dom';
import { PinIcon, ClockIcon } from '../icons/Icons';
import type { Game } from '../../types/game';

export function GameCard({ game }: { game: Game }) {
  const fillPct = game.maxPlayers > 0 ? Math.round((game.players / game.maxPlayers) * 100) : 0;
  const prizePoolBps = game.bps.first + game.bps.second + game.bps.third + game.bps.kills;
  const prizePool = (game.players * game.entryFee * prizePoolBps / 10000).toFixed(4);

  return (
    <Link to={`/game/${game.id}`} className="game-card__link">
      <div className="game-card">
        <div className="game-card__header">
          <h3 className="game-card__name">{game.title}</h3>
          <span className="game-card__fee">{game.entryFee} ETH</span>
        </div>
        <div className="game-card__meta">
          <span><PinIcon width={14} height={14} /> {game.location}</span>
          <span><ClockIcon width={14} height={14} /> {game.date}</span>
        </div>
        <div className="game-card__players">
          <span>{game.players} / {game.maxPlayers}</span>
          <div className="game-card__bar">
            <div className="game-card__bar-fill" style={{ width: `${fillPct}%` }} />
          </div>
        </div>
        <div className="game-card__pool">Prize Pool <strong>{prizePool} ETH</strong></div>
      </div>
    </Link>
  );
}
