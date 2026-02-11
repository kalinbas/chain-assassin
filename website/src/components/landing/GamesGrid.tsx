import { useGames } from '../../hooks/useGames';
import { GameCard } from './GameCard';
import { SkeletonGameCard } from '../ui/Skeleton';
import { Section } from '../layout/Section';

export function GamesGrid() {
  const { games, loading, error } = useGames();
  const now = Math.floor(Date.now() / 1000);
  const upcoming = games.filter((g) => g.phase === 'registration' && g.registrationDeadlineTs > now);

  return (
    <Section id="games" title="Upcoming Games" subtitle="Registration open — join now">
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
        {!loading && !error && upcoming.length === 0 && (
          <p style={{ gridColumn: '1/-1', textAlign: 'center', color: 'var(--text-sec)', fontSize: '1rem' }}>
            No upcoming games right now.
          </p>
        )}
        {!loading && !error && upcoming.map((game) => (
          <GameCard key={game.id} game={game} />
        ))}
      </div>
    </Section>
  );
}
