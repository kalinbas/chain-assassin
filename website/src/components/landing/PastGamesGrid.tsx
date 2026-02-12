import { Link } from 'react-router-dom';
import { Section } from '../layout/Section';
import { PinIcon, ClockIcon, TrophyIcon } from '../icons/Icons';
import { useGames } from '../../hooks/useGames';
import { gameUrl } from '../../lib/url';
import { SkeletonGameCard } from '../ui/Skeleton';
import type { Game } from '../../types/game';

function PastGameCard({ game }: { game: Game }) {
  const prizePoolBps = game.bps.first + game.bps.second + game.bps.third + game.bps.kills;
  const playerCount = Math.max(game.players, game.minPlayers);
  const totalPool = playerCount * game.entryFee + game.baseReward;
  const prizePool = (totalPool * prizePoolBps / 10000).toFixed(4);
  const isEnded = game.phase === 'ended';
  const hasWinner = game.winner1 !== 0;

  return (
    <Link to={gameUrl(game.id, game.title)} className="game-card__link">
      <div className="past-game-card">
        <div className="past-game-card__header">
          <h3 className="past-game-card__name">{game.title}</h3>
          <span className={`past-game-card__badge past-game-card__badge--${game.phase}`}>
            {game.phase.toUpperCase()}
          </span>
        </div>
        <div className="past-game-card__meta">
          <span><PinIcon width={14} height={14} /> {game.location}</span>
          <span><ClockIcon width={14} height={14} /> {game.date}</span>
        </div>
        <div className="past-game-card__stats">
          <span>{game.players} players</span>
          <span>{prizePool} ETH pool</span>
        </div>
        {isEnded && hasWinner && (
          <div className="past-game-card__result">
            <TrophyIcon width={14} height={14} />
            <span>Winner: <strong>Player #{game.winner1}</strong></span>
          </div>
        )}
        {!isEnded && (
          <div className="past-game-card__result past-game-card__result--cancelled">
            <span>Not enough players — refunds issued</span>
          </div>
        )}
      </div>
    </Link>
  );
}

export function PastGamesGrid() {
  const { games, loading } = useGames();
  const pastGames = games.filter((g) => g.phase === 'ended' || g.phase === 'cancelled');

  if (!loading && pastGames.length === 0) return null;

  return (
    <Section id="past-games" title="Past Games" subtitle="Completed hunts" alt>
      <div className="games-grid">
        {loading && (
          <>
            <SkeletonGameCard />
            <SkeletonGameCard />
          </>
        )}
        {!loading && pastGames.map((game) => (
          <PastGameCard key={game.id} game={game} />
        ))}
      </div>
    </Section>
  );
}
