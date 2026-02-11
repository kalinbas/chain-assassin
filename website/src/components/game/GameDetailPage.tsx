import { Link } from 'react-router-dom';
import type { Game } from '../../types/game';
import { BackIcon } from '../icons/Icons';
import { GameStatsGrid } from './GameStatsGrid';
import { GameMap } from './GameMap';
import { PrizeBreakdown } from './PrizeBreakdown';
import { ActivityFeed } from './ActivityFeed';
import { ShareButton } from './ShareButton';
import { Leaderboard } from './Leaderboard';
import { PhotoGallery } from './PhotoGallery';
import { SpectatorView } from './SpectatorView';
import { useSpectatorSocket } from '../../hooks/useSpectatorSocket';

function PhaseBadge({ phase }: { phase: string }) {
  return <span className={`game-detail__badge game-detail__badge--${phase}`}>{phase}</span>;
}

function LiveContent({ game }: { game: Game }) {
  const spectator = useSpectatorSocket(game.id);

  if (!spectator.connected) {
    return (
      <>
        <p style={{ color: 'var(--text-sec)', textAlign: 'center', padding: '2rem 0' }}>
          Connecting to live game...
        </p>
        {game.zoneShrinks.length > 0 && <GameMap game={game} />}
        <PrizeBreakdown game={game} />
      </>
    );
  }

  return <SpectatorView state={spectator} />;
}

export function GameDetailPage({ game }: { game: Game }) {
  const backLink = game.phase === 'ended' || game.phase === 'cancelled'
    ? '/#past-games'
    : '/#games';

  const isLive = game.phase === 'active';

  return (
    <main className="game-detail">
      <div className="container">
        <Link to={backLink} className="game-detail__back">
          <BackIcon /> Back to Games
        </Link>
        <div className="game-detail__header">
          <h1 className="game-detail__title">{game.title}</h1>
          <PhaseBadge phase={game.phase} />
          <ShareButton gameId={game.id} title={game.title} />
        </div>

        {game.phase === 'cancelled' && (
          <div className="game-detail__cancelled">
            Game was cancelled — not enough players registered ({game.players}/{game.minPlayers} minimum). Entry fees have been refunded.
          </div>
        )}

        {!isLive && <GameStatsGrid game={game} />}

        {isLive ? (
          <LiveContent game={game} />
        ) : (
          <>
            {game.phase === 'ended' && <Leaderboard game={game} />}
            {game.phase === 'ended' && <PhotoGallery gameId={game.id} />}

            {game.zoneShrinks.length > 0 && <GameMap game={game} />}

            {game.phase !== 'ended' && game.phase !== 'cancelled' && (
              <>
                <PrizeBreakdown game={game} />
                <ActivityFeed events={game.activity} />
              </>
            )}
          </>
        )}
      </div>
    </main>
  );
}
