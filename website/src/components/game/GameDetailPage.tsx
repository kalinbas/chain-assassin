import { Link } from 'react-router-dom';
import type { Game } from '../../types/game';
import { BackIcon } from '../icons/Icons';
import { GameStatsGrid } from './GameStatsGrid';
import { GameMap } from './GameMap';
import { PrizeBreakdown } from './PrizeBreakdown';
import { ZoneSchedule } from './ZoneSchedule';
import { ActivityFeed } from './ActivityFeed';
import { ShareButton } from './ShareButton';

function PhaseBadge({ phase }: { phase: string }) {
  return <span className={`game-detail__phase game-detail__phase--${phase}`}>{phase}</span>;
}

export function GameDetailPage({ game }: { game: Game }) {
  return (
    <main className="game-detail">
      <div className="container">
        <div className="game-detail__header">
          <Link to="/#games" className="game-detail__back">
            <BackIcon /> Back to Games
          </Link>
          <div className="game-detail__title-row">
            <h1 className="game-detail__title">{game.title}</h1>
            <PhaseBadge phase={game.phase} />
          </div>
          <ShareButton gameId={game.id} />
        </div>

        <GameStatsGrid game={game} />

        {game.zoneShrinks.length > 0 && <GameMap game={game} />}

        <PrizeBreakdown game={game} />

        {game.zoneShrinks.length > 0 && <ZoneSchedule zones={game.zoneShrinks} />}

        <ActivityFeed events={game.activity} />
      </div>
    </main>
  );
}
