export function Footer() {
  return (
    <footer className="footer">
      <div className="container footer__inner">
        <div className="footer__brand">
          <div className="nav__logo">
            <span className="nav__logo-chain">CHAIN</span>
            <span className="nav__logo-assassin">ASSASSIN</span>
          </div>
          <p className="footer__tagline">Hunt or be hunted. On-chain.</p>
        </div>
        <div className="footer__links">
          <a href="https://x.com/assassin_chain" target="_blank" rel="noopener">Twitter / X</a>
          <a href="https://discord.gg/SayMP2cJsp" target="_blank" rel="noopener">Discord</a>
          <a href="https://t.me/chainassassin" target="_blank" rel="noopener">Telegram</a>
          <a href="https://github.com/kalinbas/chain-assassin" target="_blank" rel="noopener">GitHub</a>
        </div>
        <div className="footer__base">
          <span className="footer__base-badge">Built on Base</span>
        </div>
        <p className="footer__copy">&copy; 2026 Chain-Assassin. All rights reserved.</p>
      </div>
    </footer>
  );
}
