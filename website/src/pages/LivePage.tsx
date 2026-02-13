import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ClockIcon, PinIcon, ShareIcon } from '../components/icons/Icons';
import { Spinner } from '../components/ui/Spinner';
import { useGames } from '../hooks/useGames';
import { trackEvent } from '../lib/analytics';
import { setPageSeo } from '../lib/seo';
import { gameShareUrl, gameUrl } from '../lib/url';
import type { Game } from '../types/game';

function formatCountdown(endsAt: number, nowTs: number): string {
  const remaining = Math.max(0, endsAt - nowTs);
  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function getLivePhaseLabel(game: Game): string {
  if (game.subPhase === 'checkin') return 'Check-In Live';
  if (game.subPhase === 'pregame') return 'Pregame Live';
  return 'In Progress';
}

function getCountdownLabel(game: Game): { label: string; endsAt: number } | null {
  if (game.subPhase === 'checkin' && game.checkinEndsAt) {
    return { label: 'Check-in ends in', endsAt: game.checkinEndsAt };
  }
  if (game.subPhase === 'pregame' && game.pregameEndsAt) {
    return { label: 'Hunt begins in', endsAt: game.pregameEndsAt };
  }
  return null;
}

export function LivePage() {
  const { games, loading, error } = useGames();
  const [copiedGameId, setCopiedGameId] = useState<number | null>(null);
  const [nowTs, setNowTs] = useState(() => Math.floor(Date.now() / 1000));
  const viewTrackedRef = useRef(false);

  const liveGames = useMemo(() => games.filter((game) => game.phase === 'active'), [games]);

  useEffect(() => {
    setPageSeo({
      title: liveGames.length > 0
        ? `Live Games (${liveGames.length}) — Chain Assassin`
        : 'Live Games — Chain Assassin',
      description: 'Watch active Chain Assassin matches in real time. Follow live eliminations, zone pressure, and leaderboard changes.',
      path: '/live',
      type: 'website',
    });
  }, [liveGames.length]);

  useEffect(() => {
    if (viewTrackedRef.current || loading) {
      return;
    }
    viewTrackedRef.current = true;
    trackEvent('live_hub_view', { live_games: liveGames.length });
  }, [loading, liveGames.length]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setNowTs(Math.floor(Date.now() / 1000));
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const handleShare = async (game: Game) => {
    const url = gameShareUrl(game.id, game.title, {
      source: 'web',
      medium: 'live_hub_share',
      campaign: 'spectator',
    });
    const shareTitle = `${game.title} is live now`;
    const shareText = `Watch this Chain Assassin match live.`;

    if (navigator.share) {
      try {
        await navigator.share({ title: shareTitle, text: shareText, url });
        trackEvent('game_share', { game_id: game.id, method: 'web_share', from: 'live_hub' });
        return;
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') {
          return;
        }
      }
    }

    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(url);
        setCopiedGameId(game.id);
        trackEvent('game_share', { game_id: game.id, method: 'clipboard', from: 'live_hub' });
        window.setTimeout(() => {
          setCopiedGameId((current) => (current === game.id ? null : current));
        }, 1800);
      } catch {
        // ignore clipboard errors
      }
    }
  };

  return (
    <main className="live-page">
      <div className="container">
        <section className="live-page__hero">
          <span className="live-page__kicker">Spectator Hub</span>
          <h1 className="live-page__title">Live Games</h1>
          <p className="live-page__sub">
            Track active matches in real time. Open any live game to watch the hunt unfold.
          </p>
        </section>

        {loading && (
          <section className="live-page__loading">
            <Spinner />
            <p>Loading live matches...</p>
          </section>
        )}

        {!loading && error && (
          <section className="live-page__empty">
            <h2>Couldn&apos;t load live games</h2>
            <p>{error}</p>
            <Link to="/" className="btn btn--outline">Back to Home</Link>
          </section>
        )}

        {!loading && !error && liveGames.length === 0 && (
          <section className="live-page__empty">
            <h2>No live games right now</h2>
            <p>Check upcoming games and come back when the next hunt starts.</p>
            <Link to="/#games" className="btn btn--outline">Browse Upcoming Games</Link>
          </section>
        )}

        {!loading && !error && liveGames.length > 0 && (
          <section className="live-page__grid">
            {liveGames.map((game) => {
              const countdown = getCountdownLabel(game);
              const totalPlayers = game.playerCount ?? game.players;
              const alivePlayers = game.aliveCount ?? totalPlayers;
              const checkedInCount = game.checkedInCount ?? 0;

              return (
                <article key={game.id} className="live-card">
                  <div className="live-card__top">
                    <span className="live-card__badge">{getLivePhaseLabel(game)}</span>
                    <div className="live-card__meta">
                      <span><PinIcon width={14} height={14} /> {game.location}</span>
                      <span><ClockIcon width={14} height={14} /> {game.date}</span>
                    </div>
                  </div>

                  <h2 className="live-card__title">{game.title}</h2>

                  <div className="live-card__stats">
                    <span>{totalPlayers} players</span>
                    <span className="live-card__dot">&middot;</span>
                    <span>{alivePlayers} alive</span>
                    {game.subPhase === 'checkin' && (
                      <>
                        <span className="live-card__dot">&middot;</span>
                        <span>{checkedInCount} checked in</span>
                      </>
                    )}
                  </div>

                  {countdown && (
                    <div className="live-card__timer">
                      <span>{countdown.label}</span>
                      <strong>{formatCountdown(countdown.endsAt, nowTs)}</strong>
                    </div>
                  )}

                  <div className="live-card__actions">
                    <Link
                      to={gameUrl(game.id, game.title)}
                      className="btn btn--primary"
                      onClick={() => {
                        trackEvent('live_game_open_click', { game_id: game.id, phase: game.subPhase ?? 'game' });
                      }}
                    >
                      Watch Live
                    </Link>
                    <button
                      type="button"
                      className="btn btn--outline live-card__share"
                      onClick={() => {
                        void handleShare(game);
                      }}
                    >
                      <ShareIcon width={14} height={14} />
                      {copiedGameId === game.id ? 'Copied' : 'Share'}
                    </button>
                  </div>
                </article>
              );
            })}
          </section>
        )}
      </div>
    </main>
  );
}
