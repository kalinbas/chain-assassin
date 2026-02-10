import { Link } from 'react-router-dom';
import type { Game } from '../../types/game';
import { BackIcon } from '../icons/Icons';
import { GameStatsGrid } from './GameStatsGrid';
import { GameMap } from './GameMap';
import { PrizeBreakdown } from './PrizeBreakdown';
import { ActivityFeed } from './ActivityFeed';
import { ShareButton } from './ShareButton';
import { Leaderboard } from './Leaderboard';

function PhaseBadge({ phase }: { phase: string }) {
  return <span className={`game-detail__phase game-detail__phase--${phase}`}>{phase}</span>;
}

export function GameDetailPage({ game }: { game: Game }) {
  const backLink = game.phase === 'ended' || game.phase === 'cancelled'
    ? '/#past-games'
    : '/#games';

  return (
    <main className="game-detail">
      <div className="container">
        <div className="game-detail__header">
          <Link to={backLink} className="game-detail__back">
            <BackIcon /> Back to Games
          </Link>
          <div className="game-detail__title-row">
            <h1 className="game-detail__title">{game.title}</h1>
            <PhaseBadge phase={game.phase} />
          </div>
          <ShareButton gameId={game.id} />
        </div>

        {game.phase === 'cancelled' && (
          <div className="game-detail__cancelled">
            Game was cancelled — not enough players registered ({game.players}/{game.minPlayers} minimum). Entry fees have been refunded.
          </div>
        )}

        <GameStatsGrid game={game} />

        {game.phase === 'ended' && <Leaderboard game={game} />}

        {game.zoneShrinks.length > 0 && <GameMap game={game} />}

        {game.phase !== 'ended' && game.phase !== 'cancelled' && (
          <>
            <PrizeBreakdown game={game} />

            <ActivityFeed events={game.activity} />
          </>
        )}
      </div>
    </main>
  );
}
