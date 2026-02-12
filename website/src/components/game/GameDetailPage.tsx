import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { Game } from '../../types/game';
import { BackIcon } from '../icons/Icons';
import { GameStatsGrid } from './GameStatsGrid';
import { GameMap } from './GameMap';
import { PrizeBreakdown } from './PrizeBreakdown';
import { ShareButton } from './ShareButton';
import { Leaderboard } from './Leaderboard';
import { PhotoGallery } from './PhotoGallery';
import { SpectatorView } from './SpectatorView';
import { SpectatorMap } from './SpectatorMap';
import { useSpectatorSocket } from '../../hooks/useSpectatorSocket';

function PhaseBadge({ phase, subPhase }: { phase: string; subPhase?: string }) {
  const label = phase === 'active' && subPhase && subPhase !== 'game' ? subPhase : phase;
  return <span className={`game-detail__badge game-detail__badge--${phase}`}>{label}</span>;
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

  if (spectator.subPhase === 'checkin') {
    return (
      <div className="game-detail__phase-info">
        <div className="game-detail__phase-icon">📍</div>
        <h2>Check-In Phase</h2>
        <p>Players are checking in at the meeting point. The game will begin once check-in ends.</p>
        {spectator.checkinEndsAt && <CountdownTimer endsAt={spectator.checkinEndsAt} label="Check-in ends in" />}
        <div className="game-detail__phase-stats">
          <div className="game-detail__phase-stat">
            <span className="game-detail__phase-stat-value">{spectator.checkedInCount}/{spectator.playerCount}</span>
            <span className="game-detail__phase-stat-label">Checked In</span>
          </div>
        </div>
        <SpectatorMap state={spectator} />
        <PrizeBreakdown game={game} />
      </div>
    );
  }

  if (spectator.subPhase === 'pregame') {
    return (
      <div className="game-detail__phase-info">
        <div className="game-detail__phase-icon">⏳</div>
        <h2>Pregame Phase</h2>
        <p>Players are spreading out and getting into position. Targets will be assigned when the countdown ends.</p>
        {spectator.pregameEndsAt && <CountdownTimer endsAt={spectator.pregameEndsAt} label="Game starts in" />}
        <div className="game-detail__phase-stats">
          <div className="game-detail__phase-stat">
            <span className="game-detail__phase-stat-value">{spectator.aliveCount}</span>
            <span className="game-detail__phase-stat-label">Players Ready</span>
          </div>
        </div>
        <SpectatorMap state={spectator} />
        <PrizeBreakdown game={game} />
      </div>
    );
  }

  return <SpectatorView state={spectator} />;
}

function CountdownTimer({ endsAt, label }: { endsAt: number; label: string }) {
  const [remaining, setRemaining] = useState(() => Math.max(0, endsAt - Math.floor(Date.now() / 1000)));

  useEffect(() => {
    const timer = setInterval(() => {
      setRemaining(Math.max(0, endsAt - Math.floor(Date.now() / 1000)));
    }, 1000);
    return () => clearInterval(timer);
  }, [endsAt]);

  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;

  return (
    <div className="game-detail__countdown">
      <div className="game-detail__countdown-label">{label}</div>
      <div className="game-detail__countdown-time">
        {String(minutes).padStart(2, '0')}:{String(seconds).padStart(2, '0')}
      </div>
    </div>
  );
}

export function GameDetailPage({ game }: { game: Game }) {
  const backLink = game.phase === 'ended' || game.phase === 'cancelled'
    ? '/#past-games'
    : '/#games';

  const isActive = game.phase === 'active';

  return (
    <main className="game-detail">
      <div className="container">
        <Link to={backLink} className="game-detail__back">
          <BackIcon /> Back to Games
        </Link>
        <div className="game-detail__header">
          <h1 className="game-detail__title">{game.title}</h1>
          <PhaseBadge phase={game.phase} subPhase={game.subPhase} />
          <ShareButton gameId={game.id} title={game.title} />
        </div>

        {game.phase === 'cancelled' && (
          <div className="game-detail__cancelled">
            Game was cancelled — not enough players registered ({game.players}/{game.minPlayers} minimum). Entry fees have been refunded.
          </div>
        )}

        {isActive ? (
          <LiveContent game={game} />
        ) : (
          <>
            <GameStatsGrid game={game} />
            {game.phase === 'ended' && <Leaderboard game={game} />}
            {game.phase === 'ended' && <PhotoGallery gameId={game.id} />}

            {game.zoneShrinks.length > 0 && <GameMap game={game} />}

            {game.phase !== 'ended' && game.phase !== 'cancelled' && (
              <PrizeBreakdown game={game} />
            )}
          </>
        )}
      </div>
    </main>
  );
}
