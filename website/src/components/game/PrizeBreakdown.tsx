import type { Game } from '../../types/game';

export function PrizeBreakdown({ game }: { game: Game }) {
  const playerCount = Math.max(game.players, game.minPlayers);
  const totalPool = playerCount * game.entryFee;
  const calc = (bps: number) => (totalPool * bps / 10000).toFixed(4);
  const pct = (bps: number) => Math.round(bps / 100);

  // Find max bps among podium places to scale bar heights proportionally
  const maxBps = Math.max(game.bps.first, game.bps.second, game.bps.third, 1);
  const barHeight = (bps: number) => Math.max(8, Math.round((bps / maxBps) * 56));

  // Collect optional fee items (only show if > 0)
  const extras: { key: string; label: string; bps: number; color: string; icon: React.ReactNode }[] = [];

  if (game.bps.kills > 0) {
    extras.push({
      key: 'kills', label: 'Top Killer', bps: game.bps.kills, color: 'kills',
      icon: (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="9" stroke="var(--danger)" strokeWidth="1.5" fill="rgba(255,59,59,0.1)"/>
          <path d="M12 8v4M12 16h.01" stroke="var(--danger)" strokeWidth="2" strokeLinecap="round"/>
        </svg>
      ),
    });
  }

  if (game.bps.creator > 0) {
    extras.push({
      key: 'creator', label: 'Creator', bps: game.bps.creator, color: 'creator',
      icon: (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
          <path d="M12 2L14.5 8.5L21 9.5L16 14L17.5 21L12 17.5L6.5 21L8 14L3 9.5L9.5 8.5L12 2Z"
            stroke="var(--primary-dim)" strokeWidth="1.5" fill="rgba(0,255,136,0.08)"/>
        </svg>
      ),
    });
  }

  if (game.bps.platform > 0) {
    extras.push({
      key: 'platform', label: 'Platform', bps: game.bps.platform, color: 'platform',
      icon: (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
          <rect x="3" y="3" width="18" height="18" rx="3" stroke="var(--text-dim)" strokeWidth="1.5" fill="none"/>
          <path d="M8 12h8M12 8v8" stroke="var(--text-dim)" strokeWidth="1.5" strokeLinecap="round"/>
        </svg>
      ),
    });
  }

  return (
    <div className="prize-breakdown">
      <h3 className="game-detail__section-title">Prize Breakdown</h3>

      <div className="prize-pool">
        <span className="prize-pool__label">Total Pool</span>
        <span className="prize-pool__amount">{totalPool.toFixed(4)} ETH</span>
      </div>

      {/* Pedestal: 2nd | 1st | 3rd */}
      <div className="prize-pedestal">
        {/* 2nd Place */}
        <div className="prize-pedestal__place prize-pedestal__place--2nd">
          <div className="prize-pedestal__icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="8" r="6" stroke="#C0C0C0" strokeWidth="1.5" fill="rgba(192,192,192,0.1)"/>
              <text x="12" y="11" textAnchor="middle" fill="#C0C0C0" fontSize="8" fontWeight="700" fontFamily="var(--font-mono)">2</text>
            </svg>
          </div>
          <span className="prize-pedestal__label">2nd Place</span>
          <span className="prize-pedestal__pct">{pct(game.bps.second)}%</span>
          <span className="prize-pedestal__amount">{calc(game.bps.second)} ETH</span>
          <div className="prize-pedestal__bar prize-pedestal__bar--2nd" style={{ height: `${barHeight(game.bps.second)}px` }}></div>
        </div>

        {/* 1st Place (tallest) */}
        <div className="prize-pedestal__place prize-pedestal__place--1st">
          <div className="prize-pedestal__icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
              <path d="M12 2L14.5 8.5L21 9.5L16 14L17.5 21L12 17.5L6.5 21L8 14L3 9.5L9.5 8.5L12 2Z"
                stroke="#FFD700" strokeWidth="1.5" fill="rgba(255,215,0,0.15)"/>
            </svg>
          </div>
          <span className="prize-pedestal__label">1st Place</span>
          <span className="prize-pedestal__pct">{pct(game.bps.first)}%</span>
          <span className="prize-pedestal__amount">{calc(game.bps.first)} ETH</span>
          <div className="prize-pedestal__bar prize-pedestal__bar--1st" style={{ height: `${barHeight(game.bps.first)}px` }}></div>
        </div>

        {/* 3rd Place */}
        <div className="prize-pedestal__place prize-pedestal__place--3rd">
          <div className="prize-pedestal__icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="8" r="6" stroke="#CD7F32" strokeWidth="1.5" fill="rgba(205,127,50,0.1)"/>
              <text x="12" y="11" textAnchor="middle" fill="#CD7F32" fontSize="8" fontWeight="700" fontFamily="var(--font-mono)">3</text>
            </svg>
          </div>
          <span className="prize-pedestal__label">3rd Place</span>
          <span className="prize-pedestal__pct">{pct(game.bps.third)}%</span>
          <span className="prize-pedestal__amount">{calc(game.bps.third)} ETH</span>
          <div className="prize-pedestal__bar prize-pedestal__bar--3rd" style={{ height: `${barHeight(game.bps.third)}px` }}></div>
        </div>
      </div>

      {/* Bottom row: optional extras (kills, creator, platform) */}
      {extras.length > 0 && (
        <div className="prize-extras" style={{ gridTemplateColumns: `repeat(${extras.length}, 1fr)` }}>
          {extras.map(item => (
            <div key={item.key} className={`prize-extras__item prize-extras__item--${item.color}`}>
              <div className="prize-extras__icon">{item.icon}</div>
              <div className="prize-extras__info">
                <span className="prize-extras__label">{item.label}</span>
                <span className="prize-extras__amount">{calc(item.bps)} ETH</span>
              </div>
              <span className="prize-extras__pct">{pct(item.bps)}%</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
