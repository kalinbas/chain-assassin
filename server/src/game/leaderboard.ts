import { getLeaderboard as dbGetLeaderboard, getPlayers } from "../db/queries.js";
import type { LeaderboardEntry } from "../utils/types.js";

/**
 * Get the current leaderboard for a game.
 * Sorted: alive players first, then by elimination timing desc, kills desc, player number asc.
 */
export function getLeaderboard(gameId: number): LeaderboardEntry[] {
  return dbGetLeaderboard(gameId);
}

/**
 * Determine the game winners from the final player state.
 *
 * Winner determination:
 * 1. winner1: Last alive player.
 * 2. Remaining order: eliminatedAt desc, then kills desc, then playerNumber asc.
 * 4. topKiller: Highest kill count overall (can overlap with winners).
 *
 * Returns address(0) for slots where BPS is 0 in the game config.
 */
export function determineWinners(
  gameId: number,
  bps2nd: number,
  bps3rd: number,
  bpsKills: number
): {
  winner1: string;
  winner2: string;
  winner3: string;
  topKiller: string;
} {
  const ZERO = "0x0000000000000000000000000000000000000000";
  const players = getPlayers(gameId);

  if (players.length === 0) {
    return { winner1: ZERO, winner2: ZERO, winner3: ZERO, topKiller: ZERO };
  }

  // Sort by: alive first, then eliminatedAt descending, kills descending, playerNumber ascending.
  const ranked = [...players].sort((a, b) => {
    // Alive players come first
    if (a.isAlive !== b.isAlive) return a.isAlive ? -1 : 1;

    // For eliminated players, later elimination = better placement.
    if (!a.isAlive && !b.isAlive) {
      const byEliminatedAt = (b.eliminatedAt ?? 0) - (a.eliminatedAt ?? 0);
      if (byEliminatedAt !== 0) return byEliminatedAt;
    }

    // Tie-breakers
    if (a.kills !== b.kills) return b.kills - a.kills;
    return a.playerNumber - b.playerNumber;
  });

  const winner1 = ranked[0]?.address ?? ZERO;
  const winner2 = bps2nd > 0 ? (ranked[1]?.address ?? ZERO) : ZERO;
  const winner3 = bps3rd > 0 ? (ranked[2]?.address ?? ZERO) : ZERO;

  // Top killer: highest kills overall
  let topKiller = ZERO;
  if (bpsKills > 0) {
    const byKills = [...players].sort((a, b) => b.kills - a.kills);
    if (byKills.length > 0 && byKills[0].kills > 0) {
      topKiller = byKills[0].address;
    }
  }

  return { winner1, winner2, winner3, topKiller };
}
