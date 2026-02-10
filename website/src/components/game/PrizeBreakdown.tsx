import type { Game } from '../../types/game';

function PrizeRow({ label, bps, amount, modifier }: { label: string; bps: number; amount: string; modifier: string }) {
  const pct = Math.round(bps / 100);
  return (
    <div className="game-detail__prize-row">
      <span className="game-detail__prize-label">{label}</span>
      <div className="game-detail__prize-bar">
        <div className={`game-detail__prize-fill game-detail__prize-fill--${modifier}`} style={{ width: `${pct}%` }}>
          {pct}%
        </div>
      </div>
      <span className="game-detail__prize-amount">{amount} ETH</span>
    </div>
  );
}

export function PrizeBreakdown({ game }: { game: Game }) {
  const playerCount = Math.max(game.players, game.minPlayers);
  const totalPool = playerCount * game.entryFee;
  const prizes = {
    first: (totalPool * game.bps.first / 10000).toFixed(4),
    second: (totalPool * game.bps.second / 10000).toFixed(4),
    third: (totalPool * game.bps.third / 10000).toFixed(4),
    kills: (totalPool * game.bps.kills / 10000).toFixed(4),
    platform: (totalPool * game.bps.platform / 10000).toFixed(4),
  };

  return (
    <div className="game-detail__prizes">
      <h3 className="game-detail__section-title">Prize Breakdown</h3>
      <PrizeRow label="1st Place" bps={game.bps.first} amount={prizes.first} modifier="1st" />
      <PrizeRow label="2nd Place" bps={game.bps.second} amount={prizes.second} modifier="2nd" />
      <PrizeRow label="3rd Place" bps={game.bps.third} amount={prizes.third} modifier="3rd" />
      <PrizeRow label="Top Killer" bps={game.bps.kills} amount={prizes.kills} modifier="kills" />
      <PrizeRow label="Platform" bps={game.bps.platform} amount={prizes.platform} modifier="platform" />
    </div>
  );
}
