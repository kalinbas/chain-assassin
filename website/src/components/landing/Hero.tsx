import { Link } from 'react-router-dom';
import { useGames } from '../../hooks/useGames';
import { trackEvent } from '../../lib/analytics';

const base = import.meta.env.BASE_URL;

export function Hero() {
  const { games } = useGames();
  const liveCount = games.filter((game) => game.phase === 'active').length;

  return (
    <section className="hero" id="hero">
      <div className="hero__bg">
        <img src={`${base}media/banner.png`} alt="Players hunting in a park with QR codes" className="hero__img" />
        <div className="hero__overlay" />
      </div>
      <div className="hero__content">
        <h1 className="hero__title">
          Hunt or Be Hunted.<br />
          <span className="text-primary">On-Chain.</span>
        </h1>
        <p className="hero__sub">Real-world elimination game. GPS-tracked zones. QR-code kills. ETH prizes on Base.</p>
        <div className="hero__ctas">
          {liveCount > 0 && (
            <Link
              to="/live"
              className="btn btn--alert btn--lg hero__live-btn"
              onClick={() => {
                trackEvent('watch_live_cta_click', { source: 'hero', live_games: liveCount });
              }}
            >
              Watch Live ({liveCount})
            </Link>
          )}
          <span style={{ position: 'relative', display: 'inline-block' }}>
            <a
              href="#"
              className="btn btn--primary btn--lg"
              onClick={(event) => {
                event.preventDefault();
                trackEvent('download_app_click', { source: 'hero' });
              }}
            >
              Download App
            </a>
            <span className="badge badge--soon btn-soon-tag">Coming soon</span>
          </span>
          <a
            href="#demo"
            className="btn btn--outline btn--lg"
            onClick={() => {
              trackEvent('watch_demo_click', { source: 'hero' });
            }}
          >
            Watch Demo
          </a>
        </div>
        <div className="hero__badges">
          <div className="badge">100+ Players</div>
          <div className="badge">Real ETH Prizes</div>
          <div className="badge">GPS + QR + Bluetooth Verified</div>
        </div>
      </div>
    </section>
  );
}
