import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import type { Game } from '../../types/game';
import { BackIcon, RadarIcon } from '../icons/Icons';
import { GameStatsGrid } from './GameStatsGrid';
import { GameMap } from './GameMap';
import { PrizeBreakdown } from './PrizeBreakdown';
import { ShareButton } from './ShareButton';
import { Leaderboard } from './Leaderboard';
import { PastGameLeaderboard } from './PastGameLeaderboard';
import { PhotoGallery } from './PhotoGallery';
import { SpectatorView } from './SpectatorView';
import { SpectatorMap } from './SpectatorMap';
import { useSpectatorSocket, type SpectatorSocketState } from '../../hooks/useSpectatorSocket';
import { loadGame } from '../../lib/api';

function PhaseBadge({ phase, subPhase }: { phase: string; subPhase?: string }) {
  const label = phase === 'active' && subPhase && subPhase !== 'game' ? subPhase : phase;
  return <span className={`game-detail__badge game-detail__badge--${phase}`}>{label}</span>;
}

function RetryIn({ retryAt }: { retryAt: number | null }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!retryAt) {
      return;
    }
    const timer = window.setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => window.clearInterval(timer);
  }, [retryAt]);

  if (!retryAt) {
    return null;
  }

  const seconds = Math.max(0, Math.ceil((retryAt - now) / 1000));
  return <>{seconds}s</>;
}

function SpectatorConnectionBanner({ state, onRefresh }: { state: SpectatorSocketState; onRefresh: () => void }) {
  const statusClass = state.connectionState === 'connected'
    ? state.isStale
      ? 'spectator-conn--warn'
      : 'spectator-conn--ok'
    : 'spectator-conn--warn';

  const text = state.connectionState === 'connected'
    ? state.isStale
      ? 'Live feed delayed. Waiting for new updates...'
      : 'Live feed synced.'
    : state.connectionState === 'reconnecting'
      ? `Connection interrupted. Reconnecting (attempt ${state.reconnectAttempt})...`
      : 'Connecting to live feed...';

  return (
    <div className={`spectator-conn ${statusClass}`}>
      <div className="spectator-conn__status">
        <span className="spectator-conn__dot" />
        <span>{text}</span>
        {state.connectionState === 'reconnecting' && (
          <span className="spectator-conn__retry">
            Retry in <RetryIn retryAt={state.nextRetryAt} />
          </span>
        )}
      </div>
      <button
        type="button"
        className="spectator-conn__refresh"
        onClick={onRefresh}
        title="Reconnect and re-sync spectator feed"
      >
        <RadarIcon width={14} height={14} />
        Refresh
      </button>
    </div>
  );
}

function LiveContent({ game, onRefreshGame }: { game: Game; onRefreshGame: () => void }) {
  const spectator = useSpectatorSocket(game.id);
  const handleRefresh = useCallback(() => {
    spectator.refresh();
    onRefreshGame();
  }, [onRefreshGame, spectator]);

  useEffect(() => {
    if (spectator.phase === 'ended' || spectator.phase === 'cancelled') {
      onRefreshGame();
    }
  }, [onRefreshGame, spectator.phase]);

  if (!spectator.connected) {
    return (
      <>
        <SpectatorConnectionBanner state={spectator} onRefresh={handleRefresh} />
        <p style={{ color: 'var(--text-sec)', textAlign: 'center', padding: '1.25rem 0 2rem' }}>
          Waiting for live stream data...
        </p>
        {game.zoneShrinks.length > 0 && <GameMap game={game} />}
        <PrizeBreakdown game={game} />
      </>
    );
  }

  if (spectator.subPhase === 'checkin') {
    return (
      <div className="game-detail__phase-info">
        <SpectatorConnectionBanner state={spectator} onRefresh={handleRefresh} />
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
        <SpectatorConnectionBanner state={spectator} onRefresh={handleRefresh} />
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

  return (
    <>
      <SpectatorConnectionBanner state={spectator} onRefresh={handleRefresh} />
      <SpectatorView state={spectator} />
    </>
  );
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
  const [currentGame, setCurrentGame] = useState(game);

  useEffect(() => {
    setCurrentGame(game);
  }, [game]);

  const refreshGameSnapshot = useCallback(() => {
    void loadGame(currentGame.id)
      .then((latest) => {
        if (latest) {
          setCurrentGame(latest);
        }
      })
      .catch(() => {
        // Keep current snapshot; WebSocket reconnect still runs.
      });
  }, [currentGame.id]);

  const backLink = currentGame.phase === 'ended' || currentGame.phase === 'cancelled'
    ? '/#past-games'
    : '/#games';

  const isActive = currentGame.phase === 'active';

  return (
    <main className="game-detail">
      <div className="container">
        <Link to={backLink} className="game-detail__back">
          <BackIcon /> Back to Games
        </Link>
        <div className="game-detail__header">
          <h1 className="game-detail__title">{currentGame.title}</h1>
          <PhaseBadge phase={currentGame.phase} subPhase={currentGame.subPhase} />
          <ShareButton gameId={currentGame.id} title={currentGame.title} />
        </div>

        {currentGame.phase === 'cancelled' && (
          <div className="game-detail__cancelled">
            Game was cancelled — not enough players registered ({currentGame.players}/{currentGame.minPlayers} minimum). Entry fees have been refunded.
          </div>
        )}

        {isActive ? (
          <LiveContent game={currentGame} onRefreshGame={refreshGameSnapshot} />
        ) : (
          <>
            <GameStatsGrid game={currentGame} />
            {currentGame.phase === 'ended' && <Leaderboard game={currentGame} />}
            {currentGame.phase === 'ended' && <PastGameLeaderboard game={currentGame} />}
            {currentGame.phase === 'ended' && <PhotoGallery gameId={currentGame.id} />}

            {currentGame.zoneShrinks.length > 0 && <GameMap game={currentGame} />}

            {currentGame.phase !== 'ended' && currentGame.phase !== 'cancelled' && (
              <PrizeBreakdown game={currentGame} />
            )}
          </>
        )}
      </div>
    </main>
  );
}
