import { useState, useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';

const NAV_LINKS = [
  { href: '#games', label: 'Games' },
  { href: '#past-games', label: 'Past Games' },
  { href: '#how-it-works', label: 'How It Works' },
  { href: '#features', label: 'Features' },
  { href: '#screenshots', label: 'Screenshots' },
  { href: '#faq', label: 'FAQ' },
];

export function Header() {
  const [isOpen, setIsOpen] = useState(false);
  const location = useLocation();

  // Close menu on route change
  useEffect(() => {
    setIsOpen(false);
    document.body.style.overflow = '';
  }, [location]);

  const toggle = () => {
    const next = !isOpen;
    setIsOpen(next);
    document.body.style.overflow = next ? 'hidden' : '';
  };

  const close = () => {
    setIsOpen(false);
    document.body.style.overflow = '';
  };

  const isLanding = location.pathname === '/';

  return (
    <header className="nav" id="nav">
      <div className="nav__inner">
        <Link to="/" className="nav__logo" onClick={close}>
          <span className="nav__logo-chain">CHAIN</span>
          <span className="nav__logo-assassin">ASSASSIN</span>
        </Link>
        <nav className={`nav__links${isOpen ? ' open' : ''}`} id="navLinks">
          {NAV_LINKS.map(({ href, label }) =>
            isLanding ? (
              <a key={href} href={href} onClick={close}>{label}</a>
            ) : (
              <Link key={href} to={`/${href}`} onClick={close}>{label}</Link>
            ),
          )}
        </nav>
        <span className="nav__cta-wrap" style={{ position: 'relative', display: 'inline-block' }}>
          <a href="#" className="btn btn--primary nav__cta">Download App</a>
          <span className="badge badge--soon btn-soon-tag">Coming soon</span>
        </span>
        <button
          className={`nav__hamburger${isOpen ? ' active' : ''}`}
          aria-label="Menu"
          onClick={toggle}
        >
          <span /><span /><span />
        </button>
      </div>
    </header>
  );
}
