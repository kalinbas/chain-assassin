import { useParams } from 'react-router-dom';
import { useGame } from '../hooks/useGame';
import { GameDetailPage } from '../components/game/GameDetailPage';
import { Spinner } from '../components/ui/Spinner';
import { getMockGame } from '../data/mockGames';

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const gameId = Number(id);

  // Mock games use IDs 900+
  const mockGame = getMockGame(gameId);
  const { game, loading, error } = useGame(mockGame ? 0 : gameId);

  if (mockGame) {
    return <GameDetailPage game={mockGame} />;
  }

  if (loading) {
    return (
      <main className="game-detail">
        <div className="container" style={{ textAlign: 'center', padding: '6rem 1rem' }}>
          <Spinner />
          <p style={{ color: 'var(--text-sec)', marginTop: '1rem' }}>Loading game from blockchain...</p>
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
            {error || `Game #${id} doesn't exist on-chain.`}
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
