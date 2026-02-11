import { Link } from 'react-router-dom';
import { useActiveGame } from '../../hooks/useActiveGame';
import { gameUrl } from '../../lib/url';

export function ActiveGame() {
  const status = useActiveGame();

  if (!status) {
    return null;
  }

  const isActive = status.phase === 'active';

  return (
    <section className="active-game">
      <div className="container">
        <Link to={gameUrl(status.gameId, status.title)} className="active-game__card">
          <div className="active-game__badge">
            <span className="active-game__dot" />
            {isActive ? 'LIVE NOW' : status.phase.toUpperCase()}
          </div>
          <h3 className="active-game__title">{status.title}</h3>
          <div className="active-game__stats">
            <span>{status.playerCount} players</span>
            {isActive && (
              <>
                <span className="active-game__sep">&middot;</span>
                <span>{status.aliveCount} alive</span>
                <span className="active-game__sep">&middot;</span>
                <span>{status.killCount} kills</span>
              </>
            )}
          </div>
          <span className="active-game__cta">{isActive ? 'Watch Live' : 'View Game'} &rarr;</span>
        </Link>
      </div>
    </section>
  );
}
