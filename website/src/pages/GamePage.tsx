import { useParams } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { useGame } from '../hooks/useGame';
import { GameDetailPage } from '../components/game/GameDetailPage';
import { Spinner } from '../components/ui/Spinner';
import { getMockGame } from '../data/mockGames';
import { parseGameId } from '../lib/url';
import { SERVER_URL } from '../config/server';
import type { Game } from '../types/game';

function useSimulatedGame(gameId: number) {
  const [game, setGame] = useState<Game | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (gameId <= 0) { setLoading(false); return; }

    fetch(`${SERVER_URL}/api/simulation/game?gameId=${gameId}`)
      .then((res) => {
        if (!res.ok) throw new Error('Not found');
        return res.json();
      })
      .then((data) => {
        if (data.game) {
          // entryFeeWei comes as string from JSON, convert to bigint
          data.game.entryFeeWei = BigInt(data.game.entryFeeWei || '0');
          setGame(data.game);
          document.title = `${data.game.title} — Chain-Assassin`;
        }
      })
      .catch(() => setError('Could not load simulated game.'))
      .finally(() => setLoading(false));
  }, [gameId]);

  return { game, loading, error };
}

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const gameId = parseGameId(id || '');

  // Mock games use IDs 900+
  const mockGame = getMockGame(gameId);

  // Simulated games use IDs 90001+
  const isSimulated = gameId >= 90001;

  const onChain = useGame(mockGame || isSimulated ? 0 : gameId);
  const simulated = useSimulatedGame(isSimulated ? gameId : 0);

  const { game, loading, error } = isSimulated ? simulated : onChain;

  if (mockGame) {
    return <GameDetailPage game={mockGame} />;
  }

  if (loading) {
    return (
      <main className="game-detail">
        <div className="container" style={{ textAlign: 'center', padding: '6rem 1rem' }}>
          <Spinner />
          <p style={{ color: 'var(--text-sec)', marginTop: '1rem' }}>
            {isSimulated ? 'Loading simulated game...' : 'Loading game from blockchain...'}
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
