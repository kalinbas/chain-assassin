import type { Game } from '../../types/game';
import { PrizeBreakdown } from './PrizeBreakdown';

export function Leaderboard({ game }: { game: Game }) {
  return <PrizeBreakdown game={game} title="Prize Distribution" />;
}
