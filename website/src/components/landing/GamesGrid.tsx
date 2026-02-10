import { useGames } from '../../hooks/useGames';
import { GameCard } from './GameCard';
import { SkeletonGameCard } from '../ui/Skeleton';
import { Section } from '../layout/Section';

export function GamesGrid() {
  const { games, loading, error } = useGames();

  return (
    <Section id="games" title="Live & Upcoming Games" subtitle="Registration open — join now">
      <div className="games-grid" id="gamesGrid">
        {loading && (
          <>
            <SkeletonGameCard />
            <SkeletonGameCard />
            <SkeletonGameCard />
          </>
        )}
        {error && (
          <p style={{ gridColumn: '1/-1', textAlign: 'center', color: 'var(--danger)', fontSize: '0.9rem' }}>
            {error}
          </p>
        )}
        {!loading && !error && games.length === 0 && (
          <p style={{ gridColumn: '1/-1', textAlign: 'center', color: 'var(--text-sec)', fontSize: '1rem' }}>
            No games found on-chain yet.
          </p>
        )}
        {!loading && !error && games.map((game) => (
          <GameCard key={game.id} game={game} />
        ))}
      </div>
    </Section>
  );
}
