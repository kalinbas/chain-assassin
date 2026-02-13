import { useEffect, useRef } from 'react';
import { useParams } from 'react-router-dom';
import { useGame } from '../hooks/useGame';
import { GameDetailPage } from '../components/game/GameDetailPage';
import { Spinner } from '../components/ui/Spinner';
import { trackEvent } from '../lib/analytics';
import { clearStructuredData, setPageSeo, setStructuredData } from '../lib/seo';
import { gameUrl, parseGameId } from '../lib/url';

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const gameId = parseGameId(id || '');
  const trackedGameIdRef = useRef<number | null>(null);

  const { game, loading, error } = useGame(gameId);

  useEffect(() => {
    if (loading) {
      return;
    }

    if (!game) {
      setPageSeo({
        title: 'Game Not Found — Chain Assassin',
        description: 'This game does not exist or is no longer available.',
        path: window.location.pathname,
        type: 'website',
      });
      clearStructuredData('game-event');
      return;
    }

    const description = `${game.title} in ${game.location}. ${game.players}/${game.maxPlayers} players registered, ${game.entryFee} ETH entry, phase: ${game.phase}.`;
    const path = gameUrl(game.id, game.title);

    setPageSeo({
      title: `${game.title} — Chain Assassin`,
      description,
      path,
      type: 'article',
    });

    setStructuredData('game-event', {
      '@context': 'https://schema.org',
      '@type': 'SportsEvent',
      name: game.title,
      startDate: new Date(game.gameDateTs * 1000).toISOString(),
      eventStatus: game.phase === 'ended'
        ? 'https://schema.org/EventCompleted'
        : game.phase === 'cancelled'
          ? 'https://schema.org/EventCancelled'
          : 'https://schema.org/EventScheduled',
      location: {
        '@type': 'Place',
        name: game.location,
        geo: {
          '@type': 'GeoCoordinates',
          latitude: game.centerLat,
          longitude: game.centerLng,
        },
      },
      organizer: {
        '@type': 'Organization',
        name: 'Chain Assassin',
      },
      offers: {
        '@type': 'Offer',
        price: game.entryFee.toString(),
        priceCurrency: 'ETH',
      },
    });

    if (trackedGameIdRef.current !== game.id) {
      trackedGameIdRef.current = game.id;
      trackEvent('game_detail_view', { game_id: game.id, phase: game.phase });
    }

    return () => {
      clearStructuredData('game-event');
    };
  }, [
    loading,
    game?.id,
    game?.title,
    game?.location,
    game?.players,
    game?.maxPlayers,
    game?.entryFee,
    game?.phase,
    game?.gameDateTs,
    game?.centerLat,
    game?.centerLng,
  ]);

  if (loading) {
    return (
      <main className="game-detail">
        <div className="container" style={{ textAlign: 'center', padding: '6rem 1rem' }}>
          <Spinner />
          <p style={{ color: 'var(--text-sec)', marginTop: '1rem' }}>
            Loading game details...
          </p>
        </div>
      </main>
    );
  }

  if (error || !game) {
    return (
      <main className="game-detail">
        <div className="container" style={{ textAlign: 'center', padding: '6rem 1rem' }}>
          <h2 style={{ color: 'var(--danger)', marginBottom: '1rem' }}>Game Not Found</h2>
          <p style={{ color: 'var(--text-sec)' }}>
            {error || `Game #${id} doesn't exist.`}
          </p>
          <a href="/" className="btn btn--outline" style={{ marginTop: '2rem', display: 'inline-block' }}>
            Back to Home
          </a>
        </div>
      </main>
    );
  }

  return <GameDetailPage game={game} />;
}
