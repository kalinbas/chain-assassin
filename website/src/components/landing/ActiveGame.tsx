import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useActiveGame } from '../../hooks/useActiveGame';
import { useGames } from '../../hooks/useGames';
import { trackEvent } from '../../lib/analytics';
import { gameUrl } from '../../lib/url';
import type { ActiveGameStatus } from '../../hooks/useActiveGame';

function Countdown({ endsAt }: { endsAt: number }) {
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
    <span className="active-game__countdown">
      {String(minutes).padStart(2, '0')}:{String(seconds).padStart(2, '0')}
    </span>
  );
}

function PhaseInfo({ status }: { status: ActiveGameStatus }) {
  if (status.phase === 'checkin') {
    return (
      <div className="active-game__phase-info">
        <div className="active-game__phase-row">
          <span className="active-game__phase-label">Players checked in</span>
          <span className="active-game__phase-value">{status.checkedInCount}/{status.playerCount}</span>
        </div>
        {status.checkinEndsAt && (
          <div className="active-game__phase-row">
            <span className="active-game__phase-label">Check-in ends in</span>
            <Countdown endsAt={status.checkinEndsAt} />
          </div>
        )}
      </div>
    );
  }

  if (status.phase === 'pregame') {
    return (
      <div className="active-game__phase-info">
        <div className="active-game__phase-row">
          <span className="active-game__phase-label">Players ready</span>
          <span className="active-game__phase-value">{status.aliveCount}</span>
        </div>
        {status.pregameEndsAt && (
          <div className="active-game__phase-row">
            <span className="active-game__phase-label">Hunt begins in</span>
            <Countdown endsAt={status.pregameEndsAt} />
          </div>
        )}
      </div>
    );
  }

  // Active game phase
  return (
    <div className="active-game__stats">
      <span>{status.playerCount} players</span>
      <span className="active-game__sep">&middot;</span>
      <span>{status.aliveCount} alive</span>
      <span className="active-game__sep">&middot;</span>
      <span>{status.killCount} kills</span>
    </div>
  );
}

function getBadgeLabel(phase: string): string {
  switch (phase) {
    case 'checkin': return 'CHECK-IN';
    case 'pregame': return 'STARTING SOON';
    case 'active': return 'LIVE NOW';
    default: return phase.toUpperCase();
  }
}

export function ActiveGame() {
  const status = useActiveGame();
  const { games } = useGames();
  const liveCount = games.filter((game) => game.phase === 'active').length;

  if (!status) {
    return null;
  }

  const isLive = status.phase === 'active';

  return (
    <section className="active-game">
      <div className="container">
        <div className="active-game__header">
          <h2 className="active-game__heading">Live Now</h2>
          {liveCount > 1 && (
            <Link
              to="/live"
              className="active-game__all-link"
              onClick={() => {
                trackEvent('watch_live_cta_click', { source: 'active_game_section', live_games: liveCount });
              }}
            >
              See all live games
            </Link>
          )}
        </div>
        <Link
          to={gameUrl(status.gameId, status.title)}
          className={`active-game__card active-game__card--${status.phase}`}
          onClick={() => {
            trackEvent('active_game_open_click', { game_id: status.gameId, phase: status.phase });
          }}
        >
          <div className={`active-game__badge active-game__badge--${status.phase}`}>
            <span className="active-game__dot" />
            {getBadgeLabel(status.phase)}
          </div>
          <h3 className="active-game__title">{status.title}</h3>
          <PhaseInfo status={status} />
          <span className="active-game__cta">{isLive ? 'Watch Live' : 'View Game'} &rarr;</span>
        </Link>
      </div>
    </section>
  );
}
