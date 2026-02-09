import { randomBytes } from "crypto";
import {
  setTargetAssignment,
  getTargetAssignment,
  getAllTargetAssignments,
  removeTargetAssignment,
  findHunterOf,
} from "../db/queries.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("targetChain");

/**
 * Fisher-Yates shuffle using crypto-random bytes.
 */
function cryptoShuffle<T>(arr: T[]): T[] {
  const shuffled = [...arr];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const bytes = randomBytes(4);
    const j = bytes.readUInt32BE(0) % (i + 1);
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled;
}

/**
 * Initialize the circular target chain for a game.
 * Shuffles players randomly and assigns: A→B→C→...→A
 */
export function initializeTargetChain(
  gameId: number,
  playerAddresses: string[]
): Map<string, string> {
  if (playerAddresses.length < 2) {
    // With 1 player, no target chain needed (auto-win)
    log.warn({ gameId, count: playerAddresses.length }, "Not enough players for target chain");
    return new Map();
  }

  const shuffled = cryptoShuffle(playerAddresses);
  const now = Math.floor(Date.now() / 1000);
  const chain = new Map<string, string>();

  for (let i = 0; i < shuffled.length; i++) {
    const hunter = shuffled[i];
    const target = shuffled[(i + 1) % shuffled.length];
    setTargetAssignment(gameId, hunter, target, now);
    chain.set(hunter, target);
  }

  log.info(
    { gameId, playerCount: shuffled.length },
    "Target chain initialized"
  );
  return chain;
}

/**
 * Process a kill: hunter kills their target.
 * Hunter inherits target's target. Target is removed from chain.
 * Returns the hunter's new target address, or null if hunter is the last one.
 */
export function processKill(
  gameId: number,
  hunterAddress: string,
  targetAddress: string
): string | null {
  const hunterTarget = getTargetAssignment(gameId, hunterAddress);
  if (!hunterTarget || hunterTarget.targetAddress !== targetAddress) {
    log.error(
      { gameId, hunter: hunterAddress, target: targetAddress },
      "Invalid kill: target mismatch"
    );
    return null;
  }

  // Get the target's target (who the dead player was hunting)
  const targetTarget = getTargetAssignment(gameId, targetAddress);
  if (!targetTarget) {
    log.error({ gameId, target: targetAddress }, "Target has no assignment");
    return null;
  }

  const newTarget = targetTarget.targetAddress;

  // Remove dead player's assignment
  removeTargetAssignment(gameId, targetAddress);

  if (newTarget === hunterAddress) {
    // Only 2 players were left, hunter wins (no more targets)
    removeTargetAssignment(gameId, hunterAddress);
    log.info({ gameId, winner: hunterAddress }, "Last kill — game should end");
    return null;
  }

  // Hunter now hunts the dead player's target
  const now = Math.floor(Date.now() / 1000);
  setTargetAssignment(gameId, hunterAddress, newTarget, now);

  log.info(
    { gameId, hunter: hunterAddress, killed: targetAddress, newTarget },
    "Kill processed, chain updated"
  );
  return newTarget;
}

/**
 * Remove an eliminated player from the chain (e.g., zone violation).
 * The hunter who was hunting this player gets reassigned.
 * Returns: { reassignedHunter, newTarget } or null if chain collapsed.
 */
export function removeFromChain(
  gameId: number,
  eliminatedAddress: string
): { reassignedHunter: string; newTarget: string } | null {
  // Find who was hunting the eliminated player
  const hunter = findHunterOf(gameId, eliminatedAddress);
  if (!hunter) {
    log.warn({ gameId, eliminated: eliminatedAddress }, "No hunter found for eliminated player");
    removeTargetAssignment(gameId, eliminatedAddress);
    return null;
  }

  // Get the eliminated player's target
  const eliminatedTarget = getTargetAssignment(gameId, eliminatedAddress);
  if (!eliminatedTarget) {
    log.warn({ gameId, eliminated: eliminatedAddress }, "Eliminated player has no target");
    return null;
  }

  const newTarget = eliminatedTarget.targetAddress;

  // Remove eliminated player's assignment
  removeTargetAssignment(gameId, eliminatedAddress);

  if (newTarget === hunter) {
    // Only 2 players were left, and one got eliminated — game should end
    removeTargetAssignment(gameId, hunter);
    log.info({ gameId, lastPlayer: hunter }, "Chain collapsed to 1 player");
    return null;
  }

  // Reassign the hunter to the eliminated player's target
  const now = Math.floor(Date.now() / 1000);
  setTargetAssignment(gameId, hunter, newTarget, now);

  log.info(
    { gameId, eliminated: eliminatedAddress, reassignedHunter: hunter, newTarget },
    "Player removed from chain"
  );
  return { reassignedHunter: hunter, newTarget };
}

/**
 * Get the current target chain as a map (for recovery/debugging).
 */
export function getChainMap(gameId: number): Map<string, string> {
  const assignments = getAllTargetAssignments(gameId);
  const map = new Map<string, string>();
  for (const a of assignments) {
    map.set(a.hunterAddress, a.targetAddress);
  }
  return map;
}

/**
 * Get the number of players remaining in the chain.
 */
export function getChainSize(gameId: number): number {
  return getAllTargetAssignments(gameId).length;
}
