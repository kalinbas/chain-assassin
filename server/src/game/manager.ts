import { config } from "../config.js";
import { createLogger } from "../utils/logger.js";
import { contractCoordToDegrees, haversineDistance } from "../utils/geo.js";
import { parseKillQrPayload } from "../utils/crypto.js";
import { GamePhase } from "../utils/types.js";
import type { GameConfig, ZoneShrink, ActiveGame, Player, ActiveSubPhase } from "../utils/types.js";
import {
  insertGame,
  insertZoneShrinks,
  insertPlayer,
  getGame,
  getPlayer,
  getPlayers,
  getAlivePlayers,
  getAlivePlayerCount,
  getPlayerCount,
  getCheckedInCount,
  updateGamePhase,
  eliminatePlayer as dbEliminatePlayer,
  incrementPlayerKills,
  insertKill,
  updateKillTxHash,
  insertLocationPing,
  getLatestLocationPing,
  setPlayerCheckedIn,
  getPlayerByNumber,
  getGamesInPhase,
  pruneLocationPings,
  updateSubPhase,
  initPlayersHeartbeat,
  updateLastHeartbeat,
  getHeartbeatExpiredPlayers,
  insertHeartbeatScan,
  getTargetAssignment,
  findHunterOf,
} from "../db/queries.js";
import { initializeTargetChain, processKill, removeFromChain, getChainSize, getChainMap } from "./targetChain.js";
import { ZoneTracker } from "./zoneTracker.js";
import { verifyKill } from "./killVerifier.js";
import { getLeaderboard, determineWinners } from "./leaderboard.js";
import * as operator from "../blockchain/operator.js";
import { getHttpProvider } from "../blockchain/client.js";

const log = createLogger("gameManager");

// In-memory active game state
const activeGames = new Map<number, {
  zoneTracker: ZoneTracker;
  tickInterval: ReturnType<typeof setInterval>;
}>();

// Per-game deadline timers (replaces 10s polling interval)
const deadlineTimers = new Map<number, ReturnType<typeof setTimeout>>();

// Per-game checkin timers — fires when checkin period ends, transitions to pregame
const checkinTimers = new Map<number, ReturnType<typeof setTimeout>>();

// Per-game pregame timers — fires when pregame countdown ends
const pregameTimers = new Map<number, ReturnType<typeof setTimeout>>();

// Simulated game IDs — skip on-chain operator calls for these
const simulatedGames = new Set<number>();

// Hybrid simulated games — registered on-chain, simulated off-chain.
// endGame still goes on-chain for these (players paid real entry fees).
const hybridSimulatedGames = new Set<number>();

export function addSimulatedGame(gameId: number): void {
  simulatedGames.add(gameId);
}

export function addHybridSimulatedGame(gameId: number): void {
  simulatedGames.add(gameId);
  hybridSimulatedGames.add(gameId);
}

export function removeSimulatedGame(gameId: number): void {
  simulatedGames.delete(gameId);
  hybridSimulatedGames.delete(gameId);
}

export function isSimulatedGame(gameId: number): boolean {
  return simulatedGames.has(gameId);
}

export function isHybridSimulatedGame(gameId: number): boolean {
  return hybridSimulatedGames.has(gameId);
}

// Broadcast function — injected from WebSocket server
let broadcastFn: ((gameId: number, message: Record<string, unknown>) => void) | null = null;
let sendToPlayerFn: ((gameId: number, address: string, message: Record<string, unknown>) => void) | null = null;
let spectatorBroadcastFn: ((gameId: number, message: Record<string, unknown>) => void) | null = null;

/**
 * Set the broadcast function (called by WebSocket server on init).
 */
export function setBroadcast(
  broadcast: (gameId: number, message: Record<string, unknown>) => void,
  sendToPlayer: (gameId: number, address: string, message: Record<string, unknown>) => void
): void {
  broadcastFn = broadcast;
  sendToPlayerFn = sendToPlayer;
}

/**
 * Set the spectator broadcast function (called by WebSocket server on init).
 */
export function setSpectatorBroadcast(
  fn: (gameId: number, message: Record<string, unknown>) => void
): void {
  spectatorBroadcastFn = fn;
}

function broadcast(gameId: number, message: Record<string, unknown>): void {
  if (broadcastFn) broadcastFn(gameId, message);
  if (spectatorBroadcastFn) spectatorBroadcastFn(gameId, message);
}

/**
 * Public broadcast — used by simulator for item events.
 */
export function broadcastToGame(gameId: number, message: Record<string, unknown>): void {
  broadcast(gameId, message);
}

function sendToPlayer(gameId: number, address: string, message: Record<string, unknown>): void {
  if (sendToPlayerFn) sendToPlayerFn(gameId, address, message);
}

// ============ Game Lifecycle ============

/**
 * Handle a new game created on-chain.
 */
export function onGameCreated(gameConfig: GameConfig, shrinks: ZoneShrink[]): void {
  insertGame(gameConfig);
  insertZoneShrinks(gameConfig.gameId, shrinks);
  scheduleDeadlineCheck(gameConfig);
  log.info({ gameId: gameConfig.gameId, title: gameConfig.title }, "Game tracked");
}

/**
 * Handle a player registration event from the chain.
 */
export function onPlayerRegistered(gameId: number, playerAddress: string, playerCount: number): void {
  insertPlayer(gameId, playerAddress, playerCount);
  log.info({ gameId, player: playerAddress, playerCount }, "Player registered");

  broadcast(gameId, {
    type: "player:registered",
    address: playerAddress,
    playerCount,
  });
}

/**
 * Start a game — called after operator.startGame() tx confirms.
 * Transitions to ACTIVE phase with sub_phase='checkin'.
 * Players must check in during this period or they'll be eliminated.
 */
export function onGameStarted(gameId: number): void {
  cancelDeadlineTimer(gameId);

  const game = getGame(gameId);
  if (!game) {
    log.error({ gameId }, "Game not found for start");
    return;
  }

  const now = Math.floor(Date.now() / 1000);

  // Set phase to ACTIVE with sub_phase='checkin'
  updateGamePhase(gameId, GamePhase.ACTIVE, { startedAt: now, subPhase: "checkin" });

  broadcast(gameId, {
    type: "game:checkin_started",
    checkinDurationSeconds: config.checkinDurationSeconds,
  });

  // Schedule checkin end timer
  const timer = setTimeout(() => {
    checkinTimers.delete(gameId);
    completeCheckin(gameId);
  }, config.checkinDurationSeconds * 1000);
  checkinTimers.set(gameId, timer);

  log.info({ gameId, checkinDurationSeconds: config.checkinDurationSeconds }, "Check-in phase started");
}

/**
 * Complete the check-in phase — eliminate non-checked-in players, then transition to pregame.
 */
function completeCheckin(gameId: number): void {
  const game = getGame(gameId);
  if (!game || game.phase !== GamePhase.ACTIVE || game.subPhase !== "checkin") return;

  const now = Math.floor(Date.now() / 1000);

  // Eliminate players who didn't check in
  const players = getPlayers(gameId);
  for (const player of players) {
    if (!player.checkedIn && player.isAlive) {
      dbEliminatePlayer(gameId, player.address, "no_checkin", now);
      log.info({ gameId, address: player.address }, "Eliminated for no check-in");
      broadcast(gameId, {
        type: "player:eliminated",
        player: player.address,
        eliminator: "no_checkin",
        reason: "no_checkin",
      });
    }
  }

  // Check if any players remain after elimination
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount === 0) {
    log.info({ gameId }, "No checked-in players, cancelling game");
    onGameCancelled(gameId);
    return;
  }

  // Transition to pregame
  updateSubPhase(gameId, "pregame");

  const checkedInCount = getCheckedInCount(gameId);

  broadcast(gameId, {
    type: "game:pregame_started",
    pregameDurationSeconds: config.pregameDurationSeconds,
    checkedInCount,
    playerCount: aliveCount,
  });

  // Schedule pregame timer
  const timer = setTimeout(() => {
    pregameTimers.delete(gameId);
    completePregame(gameId);
  }, config.pregameDurationSeconds * 1000);
  pregameTimers.set(gameId, timer);

  log.info({ gameId, aliveCount, checkedInCount, pregameDurationSeconds: config.pregameDurationSeconds }, "Pregame started");
}

/**
 * Complete the pregame phase — assign targets, start zone tracker, heartbeats, game tick.
 * Transitions sub_phase from 'pregame' to 'game'.
 */
export function completePregame(gameId: number): void {
  const game = getGame(gameId);
  if (!game) {
    log.error({ gameId }, "Game not found for pregame completion");
    return;
  }

  const now = Math.floor(Date.now() / 1000);

  // Update sub_phase to 'game'
  updateSubPhase(gameId, "game");

  // Get alive (checked-in) players only
  const alivePlayers = getAlivePlayers(gameId);
  const addresses = alivePlayers.map((p) => p.address);

  // Initialize target chain
  const chain = initializeTargetChain(gameId, addresses);

  // Initialize zone tracker
  const zoneTrackerFull = ZoneTracker.fromDb(gameId, game.centerLat, game.centerLng, game.startedAt ?? now);

  // Start game tick (1 second interval)
  const tickInterval = setInterval(() => gameTick(gameId), 1000);

  activeGames.set(gameId, { zoneTracker: zoneTrackerFull, tickInterval });

  // Initialize heartbeats for alive players
  initPlayersHeartbeat(gameId, now);

  // Notify all players of their targets + hunter number
  for (const [hunter, target] of chain) {
    const targetPlayer = getPlayer(gameId, target);
    const hunterOfHunter = findHunterOf(gameId, hunter);
    const hunterOfHunterPlayer = hunterOfHunter ? getPlayer(gameId, hunterOfHunter) : null;
    sendToPlayer(gameId, hunter, {
      type: "game:started",
      target: {
        address: target,
        playerNumber: targetPlayer?.playerNumber ?? 0,
      },
      hunterPlayerNumber: hunterOfHunterPlayer?.playerNumber ?? 0,
      heartbeatDeadline: now + config.heartbeatIntervalSeconds,
      heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
      zone: zoneTrackerFull.getZoneState(),
    });
  }

  broadcast(gameId, {
    type: "game:started_broadcast",
    playerCount: addresses.length,
  });

  log.info({ gameId, playerCount: addresses.length }, "Pregame completed, game started");
}

/**
 * Process a kill submission from a player.
 */
export async function handleKillSubmission(
  gameId: number,
  hunterAddress: string,
  qrPayload: string,
  hunterLat: number,
  hunterLng: number,
  bleNearbyAddresses: string[]
): Promise<{ success: boolean; error?: string }> {
  // Reject kills during pregame
  const game = getGame(gameId);
  if (game && game.subPhase !== "game") {
    return { success: false, error: "Game has not started yet" };
  }

  // Verify the kill
  const result = verifyKill(
    gameId,
    hunterAddress,
    qrPayload,
    hunterLat,
    hunterLng,
    bleNearbyAddresses
  );

  if (!result.valid || !result.targetAddress) {
    return { success: false, error: result.error };
  }

  const targetAddress = result.targetAddress;
  const now = Math.floor(Date.now() / 1000);

  // Record kill in DB
  const killId = insertKill({
    gameId,
    hunterAddress,
    targetAddress,
    timestamp: now,
    hunterLat,
    hunterLng,
    targetLat: result.targetLat ?? null,
    targetLng: result.targetLng ?? null,
    distanceMeters: result.distanceMeters ?? null,
    txHash: null,
  });

  // Update player states
  incrementPlayerKills(gameId, hunterAddress);
  dbEliminatePlayer(gameId, targetAddress, hunterAddress, now);

  // Update target chain
  const newTarget = processKill(gameId, hunterAddress, targetAddress);

  // Clear zone tracking for dead player
  const active = activeGames.get(gameId);
  if (active) {
    active.zoneTracker.clearPlayer(targetAddress);
  }

  // Submit on-chain (fire and forget — don't block the response)
  if (!isSimulatedGame(gameId)) {
    operator
      .recordKill(gameId, hunterAddress, targetAddress)
      .then((tx) => {
        updateKillTxHash(killId, tx.txHash);
      })
      .catch((err) => {
        log.error({ gameId, killId, error: err.message }, "Failed to record kill on-chain");
      });
  }

  // Get updated hunter stats
  const hunter = getPlayer(gameId, hunterAddress);

  // Broadcast kill event
  broadcast(gameId, {
    type: "kill:recorded",
    hunter: hunterAddress,
    target: targetAddress,
    hunterKills: hunter?.kills ?? 0,
  });

  // Broadcast elimination
  broadcast(gameId, {
    type: "player:eliminated",
    player: targetAddress,
    eliminator: hunterAddress,
    reason: "killed",
  });

  // Send new target to hunter (+ their new hunter number, since chain shifted)
  if (newTarget) {
    const newTargetPlayer = getPlayer(gameId, newTarget);
    const hunterOfKiller = findHunterOf(gameId, hunterAddress);
    const hunterOfKillerPlayer = hunterOfKiller ? getPlayer(gameId, hunterOfKiller) : null;
    sendToPlayer(gameId, hunterAddress, {
      type: "target:assigned",
      target: {
        address: newTarget,
        playerNumber: newTargetPlayer?.playerNumber ?? 0,
      },
      hunterPlayerNumber: hunterOfKillerPlayer?.playerNumber ?? 0,
    });

    // Also notify the new target that their hunter changed (dead player's target now has a new hunter)
    const hunterOfNewTarget = findHunterOf(gameId, newTarget);
    if (hunterOfNewTarget) {
      const hunterOfNewTargetPlayer = getPlayer(gameId, hunterOfNewTarget);
      sendToPlayer(gameId, newTarget, {
        type: "hunter:updated",
        hunterPlayerNumber: hunterOfNewTargetPlayer?.playerNumber ?? 0,
      });
    }
  }

  // Broadcast leaderboard update
  broadcast(gameId, {
    type: "leaderboard:update",
    entries: getLeaderboard(gameId),
  });

  // Check if game should end
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount <= 1) {
    await endGameWithWinners(gameId);
  }

  return { success: true };
}

/**
 * Process a player location update.
 */
export function handleLocationUpdate(
  gameId: number,
  address: string,
  lat: number,
  lng: number
): void {
  const active = activeGames.get(gameId);
  if (!active) return;

  const now = Math.floor(Date.now() / 1000);
  const { inZone, secondsRemaining } = active.zoneTracker.processLocation(
    address,
    lat,
    lng,
    now
  );

  insertLocationPing({
    gameId,
    address,
    lat,
    lng,
    timestamp: now,
    isInZone: inZone,
  });

  if (!inZone && secondsRemaining !== undefined) {
    sendToPlayer(gameId, address, {
      type: "zone:warning",
      secondsRemaining,
      inZone: false,
    });
  }
}

/**
 * Handle check-in from a player (before game starts).
 *
 * Viral check-in: the first 5% of registered players (min 1) can check in
 * via GPS only, seeding the chain. All subsequent players must also scan
 * the QR code of an already-checked-in player (proving physical co-location).
 */
export function handleCheckin(
  gameId: number,
  address: string,
  lat: number,
  lng: number,
  qrPayload?: string,
  bluetoothId?: string
): { success: boolean; error?: string } {
  const game = getGame(gameId);
  if (!game) return { success: false, error: "Game not found" };

  // Only allow check-ins during ACTIVE/checkin sub-phase
  if (game.phase !== GamePhase.ACTIVE || game.subPhase !== "checkin") {
    return { success: false, error: "Check-in period has ended" };
  }

  const player = getPlayer(gameId, address);
  if (!player) return { success: false, error: "Not registered" };
  if (player.checkedIn) return { success: false, error: "Already checked in" };

  // Verify player is near the meeting point (or zone center as fallback)
  const meetLatDeg = game.meetingLat !== 0
    ? contractCoordToDegrees(game.meetingLat)
    : contractCoordToDegrees(game.centerLat);
  const meetLngDeg = game.meetingLng !== 0
    ? contractCoordToDegrees(game.meetingLng)
    : contractCoordToDegrees(game.centerLng);
  const dist = haversineDistance(lat, lng, meetLatDeg, meetLngDeg);

  // Allow check-in within 5km of meeting point (generous)
  if (dist > 5000) {
    return { success: false, error: "Too far from meeting point" };
  }

  // Viral check-in: first 5% of players (min 1) are GPS-only, rest need QR
  const alreadyCheckedIn = getCheckedInCount(gameId);
  const totalPlayers = getPlayerCount(gameId);
  const gpsOnlySlots = Math.max(1, Math.ceil(totalPlayers * 0.05));
  if (alreadyCheckedIn >= gpsOnlySlots) {
    if (!qrPayload) {
      return { success: false, error: "Scan a checked-in player's QR code" };
    }

    const qr = parseKillQrPayload(qrPayload);
    if (!qr) {
      return { success: false, error: "Invalid QR code" };
    }
    if (qr.gameId !== gameId) {
      return { success: false, error: "QR code is for a different game" };
    }

    const scannedPlayer = getPlayerByNumber(gameId, qr.playerNumber);
    if (!scannedPlayer) {
      return { success: false, error: "Unknown player in QR code" };
    }
    if (!scannedPlayer.checkedIn) {
      return { success: false, error: "Scanned player has not checked in yet" };
    }
    if (scannedPlayer.address === address) {
      return { success: false, error: "Cannot scan your own QR code" };
    }
  }

  setPlayerCheckedIn(gameId, address, bluetoothId);
  const checkedIn = getCheckedInCount(gameId);
  const total = getPlayerCount(gameId);

  broadcast(gameId, {
    type: "checkin:update",
    checkedInCount: checkedIn,
    totalPlayers: total,
    player: address,
    bluetoothId: bluetoothId ?? null,
  });

  return { success: true };
}

// ============ Game Tick ============

/**
 * Called every second for active games.
 * Handles zone shrinks and out-of-zone eliminations.
 */
async function gameTick(gameId: number): Promise<void> {
  const active = activeGames.get(gameId);
  if (!active) return;

  const now = Math.floor(Date.now() / 1000);

  // Check zone shrink
  const newZone = active.zoneTracker.tick();
  if (newZone) {
    broadcast(gameId, {
      type: "zone:shrink",
      ...newZone,
    });
  }

  // Check for expired out-of-zone players
  const expired = active.zoneTracker.getExpiredPlayers(now);
  for (const address of expired) {
    await handleZoneElimination(gameId, address);
  }

  // Check heartbeat timeouts (only if enough players remain)
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount > config.heartbeatDisableThreshold) {
    const heartbeatExpired = getHeartbeatExpiredPlayers(gameId, now, config.heartbeatIntervalSeconds);
    for (const player of heartbeatExpired) {
      await handleHeartbeatElimination(gameId, player.address);
    }
  }

  // Broadcast player positions to spectators every 2 seconds
  if (now % 2 === 0 && spectatorBroadcastFn) {
    const alivePlayers = getAlivePlayers(gameId);
    const positions = alivePlayers.map((p) => {
      const ping = getLatestLocationPing(gameId, p.address);
      return {
        address: p.address,
        playerNumber: p.playerNumber,
        lat: ping?.lat ?? null,
        lng: ping?.lng ?? null,
        isAlive: true,
        kills: p.kills,
      };
    });

    // Build hunt links from target chain
    const chainMap = getChainMap(gameId);
    const huntLinks: { hunter: string; target: string }[] = [];
    for (const [hunter, target] of chainMap) {
      huntLinks.push({ hunter, target });
    }

    spectatorBroadcastFn(gameId, {
      type: "spectator:positions",
      players: positions,
      zone: active.zoneTracker.getZoneState(),
      aliveCount: alivePlayers.length,
      huntLinks,
    });
  }

  // Prune old location pings every 60 seconds
  if (now % 60 === 0) {
    pruneLocationPings(gameId, 300); // keep last 5 minutes
  }
}

/**
 * Eliminate a player for being out of zone too long.
 */
async function handleZoneElimination(gameId: number, address: string): Promise<void> {
  const now = Math.floor(Date.now() / 1000);

  // Check if player is still alive (might have been killed in the meantime)
  const player = getPlayer(gameId, address);
  if (!player || !player.isAlive) {
    const active = activeGames.get(gameId);
    active?.zoneTracker.clearPlayer(address);
    return;
  }

  log.info({ gameId, address }, "Eliminating player for zone violation");

  // Update DB
  dbEliminatePlayer(gameId, address, "zone", now);

  // Update target chain
  const reassignment = removeFromChain(gameId, address);

  // Clear zone tracking
  const active = activeGames.get(gameId);
  active?.zoneTracker.clearPlayer(address);

  // Submit on-chain
  if (!isSimulatedGame(gameId)) {
    operator.eliminatePlayer(gameId, address).catch((err) => {
      log.error({ gameId, address, error: err.message }, "Failed to eliminate player on-chain");
    });
  }

  // Broadcast elimination
  broadcast(gameId, {
    type: "player:eliminated",
    player: address,
    eliminator: "zone",
    reason: "zone_violation",
  });

  // Notify reassigned hunter of new target + their updated hunter number
  if (reassignment) {
    const newTargetPlayer = getPlayer(gameId, reassignment.newTarget);
    const hunterOfReassigned = findHunterOf(gameId, reassignment.reassignedHunter);
    const hunterOfReassignedPlayer = hunterOfReassigned ? getPlayer(gameId, hunterOfReassigned) : null;
    sendToPlayer(gameId, reassignment.reassignedHunter, {
      type: "target:assigned",
      target: {
        address: reassignment.newTarget,
        playerNumber: newTargetPlayer?.playerNumber ?? 0,
      },
      hunterPlayerNumber: hunterOfReassignedPlayer?.playerNumber ?? 0,
    });

    // Also notify the new target that their hunter changed
    const hunterOfNewTarget = findHunterOf(gameId, reassignment.newTarget);
    if (hunterOfNewTarget) {
      const hunterOfNewTargetPlayer = getPlayer(gameId, hunterOfNewTarget);
      sendToPlayer(gameId, reassignment.newTarget, {
        type: "hunter:updated",
        hunterPlayerNumber: hunterOfNewTargetPlayer?.playerNumber ?? 0,
      });
    }
  }

  // Broadcast leaderboard update
  broadcast(gameId, {
    type: "leaderboard:update",
    entries: getLeaderboard(gameId),
  });

  // Check if game should end
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount <= 1) {
    await endGameWithWinners(gameId);
  }
}

/**
 * Eliminate a player for missing their heartbeat scan deadline.
 */
async function handleHeartbeatElimination(gameId: number, address: string): Promise<void> {
  const now = Math.floor(Date.now() / 1000);

  // Check if player is still alive
  const player = getPlayer(gameId, address);
  if (!player || !player.isAlive) return;

  log.info({ gameId, address, playerNumber: player.playerNumber }, "Eliminating player for missed heartbeat");

  // Update DB
  dbEliminatePlayer(gameId, address, "heartbeat", now);

  // Update target chain
  const reassignment = removeFromChain(gameId, address);

  // Clear zone tracking
  const active = activeGames.get(gameId);
  active?.zoneTracker.clearPlayer(address);

  // Submit on-chain
  if (!isSimulatedGame(gameId)) {
    operator.eliminatePlayer(gameId, address).catch((err) => {
      log.error({ gameId, address, error: err.message }, "Failed to eliminate player on-chain (heartbeat)");
    });
  }

  // Broadcast elimination
  broadcast(gameId, {
    type: "player:eliminated",
    player: address,
    eliminator: "heartbeat",
    reason: "heartbeat_timeout",
  });

  // Notify reassigned hunter of new target
  if (reassignment) {
    const newTargetPlayer = getPlayer(gameId, reassignment.newTarget);
    const hunterOfReassigned = findHunterOf(gameId, reassignment.reassignedHunter);
    const hunterOfReassignedPlayer = hunterOfReassigned ? getPlayer(gameId, hunterOfReassigned) : null;
    sendToPlayer(gameId, reassignment.reassignedHunter, {
      type: "target:assigned",
      target: {
        address: reassignment.newTarget,
        playerNumber: newTargetPlayer?.playerNumber ?? 0,
      },
      hunterPlayerNumber: hunterOfReassignedPlayer?.playerNumber ?? 0,
    });

    // Notify the new target that their hunter changed
    const hunterOfNewTarget = findHunterOf(gameId, reassignment.newTarget);
    if (hunterOfNewTarget) {
      const hunterOfNewTargetPlayer = getPlayer(gameId, hunterOfNewTarget);
      sendToPlayer(gameId, reassignment.newTarget, {
        type: "hunter:updated",
        hunterPlayerNumber: hunterOfNewTargetPlayer?.playerNumber ?? 0,
      });
    }
  }

  // Broadcast leaderboard update
  broadcast(gameId, {
    type: "leaderboard:update",
    entries: getLeaderboard(gameId),
  });

  // Check if game should end
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount <= 1) {
    await endGameWithWinners(gameId);
  }
}

/**
 * Process a heartbeat scan submission.
 * Only the scanned player (whose QR was read) gets their heartbeat refreshed.
 */
export function handleHeartbeatScan(
  gameId: number,
  scannerAddress: string,
  qrPayload: string,
  lat: number,
  lng: number,
  bleNearbyAddresses: string[]
): { success: boolean; error?: string; scannedPlayerNumber?: number } {
  // 1. Verify game is active and in 'game' sub-phase
  const game = getGame(gameId);
  if (!game || game.phase !== GamePhase.ACTIVE) {
    return { success: false, error: "Game is not active" };
  }
  if (game.subPhase !== "game") {
    return { success: false, error: "Game has not started yet" };
  }

  // 2. Check if heartbeat is disabled (too few players)
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount <= config.heartbeatDisableThreshold) {
    return { success: false, error: "Heartbeat is disabled (endgame)" };
  }

  // 3. Verify scanner is alive
  const scanner = getPlayer(gameId, scannerAddress);
  if (!scanner || !scanner.isAlive) {
    return { success: false, error: "Scanner is not alive" };
  }

  // 4. Parse QR payload
  const qr = parseKillQrPayload(qrPayload);
  if (!qr) {
    return { success: false, error: "Invalid QR code format" };
  }
  if (qr.gameId !== gameId) {
    return { success: false, error: "QR code is for a different game" };
  }

  // 5. Resolve scanned player
  const scannedPlayer = getPlayerByNumber(gameId, qr.playerNumber);
  if (!scannedPlayer) {
    return { success: false, error: "Player not found for QR code" };
  }
  if (!scannedPlayer.isAlive) {
    return { success: false, error: "Scanned player is eliminated" };
  }

  // 6. Cannot scan yourself
  if (scannedPlayer.address === scannerAddress) {
    return { success: false, error: "Cannot scan your own QR code" };
  }

  // 7. Relationship check: cannot heartbeat with your target or your hunter
  const scannerTarget = getTargetAssignment(gameId, scannerAddress);
  if (scannerTarget && scannerTarget.targetAddress === scannedPlayer.address) {
    return { success: false, error: "Cannot heartbeat with your target" };
  }
  const scannerHunter = findHunterOf(gameId, scannerAddress);
  if (scannerHunter && scannerHunter === scannedPlayer.address) {
    return { success: false, error: "Cannot heartbeat with your hunter" };
  }

  // 8. GPS proximity check
  const scannedPing = getLatestLocationPing(gameId, scannedPlayer.address);
  let distanceMeters: number | null = null;
  if (scannedPing) {
    distanceMeters = haversineDistance(lat, lng, scannedPing.lat, scannedPing.lng);
    if (distanceMeters > config.heartbeatProximityMeters) {
      return { success: false, error: `Too far from player (${Math.round(distanceMeters)}m, max ${config.heartbeatProximityMeters}m)` };
    }
  }

  // 9. BLE proximity check
  if (config.bleRequired) {
    const bleAddresses = bleNearbyAddresses.map((a) => a.toLowerCase());
    if (!bleAddresses.includes(scannedPlayer.address)) {
      return { success: false, error: "Player not detected via Bluetooth" };
    }
  }

  // 10. Refresh the SCANNED player's heartbeat (not the scanner's)
  const now = Math.floor(Date.now() / 1000);
  updateLastHeartbeat(gameId, scannedPlayer.address, now);

  // 11. Record the scan
  insertHeartbeatScan({
    gameId,
    scannerAddress,
    scannedAddress: scannedPlayer.address,
    timestamp: now,
    scannerLat: lat,
    scannerLng: lng,
    distanceMeters,
  });

  // 12. Notify the scanned player their heartbeat was refreshed
  sendToPlayer(gameId, scannedPlayer.address, {
    type: "heartbeat:refreshed",
    refreshedUntil: now + config.heartbeatIntervalSeconds,
  });

  // 13. Notify the scanner of success
  sendToPlayer(gameId, scannerAddress, {
    type: "heartbeat:scan_success",
    scannedPlayerNumber: scannedPlayer.playerNumber,
  });

  log.info(
    { gameId, scanner: scannerAddress, scanned: scannedPlayer.address, distanceMeters },
    "Heartbeat scan processed"
  );

  return { success: true, scannedPlayerNumber: scannedPlayer.playerNumber };
}

/**
 * End a game by determining winners and calling on-chain endGame.
 */
async function endGameWithWinners(gameId: number): Promise<void> {
  const game = getGame(gameId);
  if (!game) return;

  const { winner1, winner2, winner3, topKiller } = determineWinners(
    gameId,
    game.bps2nd,
    game.bps3rd,
    game.bpsKills
  );

  const now = Math.floor(Date.now() / 1000);

  log.info(
    { gameId, winner1, winner2, winner3, topKiller },
    "Ending game with winners"
  );

  try {
    if (!isSimulatedGame(gameId) || isHybridSimulatedGame(gameId)) {
      await operator.endGame(gameId, winner1, winner2, winner3, topKiller);
    }
    updateGamePhase(gameId, GamePhase.ENDED, {
      endedAt: now,
      winner1,
      winner2,
      winner3,
      topKiller,
    });

    broadcast(gameId, {
      type: "game:ended",
      winner1,
      winner2,
      winner3,
      topKiller,
    });
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Failed to end game on-chain");
  }

  cleanupActiveGame(gameId);
}

/**
 * Handle a game cancelled event.
 */
export function onGameCancelled(gameId: number): void {
  cancelDeadlineTimer(gameId);
  updateGamePhase(gameId, GamePhase.CANCELLED);
  broadcast(gameId, { type: "game:cancelled", gameId });
  cleanupActiveGame(gameId);
  log.info({ gameId }, "Game cancelled");
}

/**
 * Handle game ended event from chain (confirmation).
 */
export function onGameEnded(
  gameId: number,
  winner1: string,
  winner2: string,
  winner3: string,
  topKiller: string
): void {
  const now = Math.floor(Date.now() / 1000);
  updateGamePhase(gameId, GamePhase.ENDED, {
    endedAt: now,
    winner1,
    winner2,
    winner3,
    topKiller,
  });
  cleanupActiveGame(gameId);
  log.info({ gameId, winner1, winner2, winner3, topKiller }, "Game ended (chain confirmed)");
}

// ============ Deadline Scheduling ============

/**
 * Schedule a one-shot timer for the game's gameDate.
 * When the timer fires, checkAutoStart() runs and decides whether to start or skip.
 * The contract only allows startGame() after gameDate is reached.
 */
export function scheduleDeadlineCheck(config: GameConfig): void {
  // Cancel any existing timer for this game
  cancelDeadlineTimer(config.gameId);

  const nowSec = Math.floor(Date.now() / 1000);
  // Schedule at gameDate (when contract allows startGame)
  const delaySec = Math.max(0, config.gameDate - nowSec + 2);
  const delayMs = delaySec * 1000;

  const timer = setTimeout(async () => {
    deadlineTimers.delete(config.gameId);
    try {
      await checkAutoStart();
    } catch (err) {
      log.error({ gameId: config.gameId, error: (err as Error).message }, "Scheduled auto-start check failed");
    }
  }, delayMs);

  deadlineTimers.set(config.gameId, timer);
  log.info({ gameId: config.gameId, delaySec }, "Deadline check scheduled");
}

/**
 * Cancel a pending deadline timer for a game.
 */
export function cancelDeadlineTimer(gameId: number): void {
  const timer = deadlineTimers.get(gameId);
  if (timer) {
    clearTimeout(timer);
    deadlineTimers.delete(gameId);
  }
}

// ============ Auto-Start Check ============

/**
 * Check registration-phase games that should be started.
 * Called by deadline timer or admin endpoint.
 */
export async function checkAutoStart(): Promise<void> {
  // Use on-chain block timestamp — critical for Anvil time-warp testing
  // and ensures we're in sync with the chain's view of time
  const block = await getHttpProvider().getBlock("latest");
  const chainTime = block?.timestamp ?? Math.floor(Date.now() / 1000);
  const regGames = getGamesInPhase(GamePhase.REGISTRATION);

  for (const game of regGames) {
    // Only start at or after registration deadline
    if (chainTime < game.registrationDeadline) continue;

    const playerCount = getPlayerCount(game.gameId);
    if (playerCount < game.minPlayers) {
      log.info(
        { gameId: game.gameId, playerCount, minPlayers: game.minPlayers },
        "Not enough players at deadline, skipping start"
      );
      continue;
    }

    log.info(
      { gameId: game.gameId, playerCount },
      "Auto-starting game (deadline reached)"
    );

    try {
      await operator.startGame(game.gameId);
      // onGameStarted will be called from the event listener when tx confirms
    } catch (err) {
      log.error(
        { gameId: game.gameId, error: (err as Error).message },
        "Failed to auto-start game"
      );
    }
  }
}

// ============ Recovery ============

/**
 * Recover games from database on startup.
 * - ACTIVE games: restore zone trackers and tick intervals
 * - REGISTRATION games: schedule deadline timers
 */
export function recoverGames(): void {
  // Recover active games
  const activeDbGames = getGamesInPhase(GamePhase.ACTIVE);
  for (const game of activeDbGames) {
    const fullGame = getGame(game.gameId);
    if (!fullGame || !fullGame.startedAt) continue;

    if (fullGame.subPhase === "checkin") {
      // Recover checkin timer — calculate remaining time
      const elapsed = Math.floor(Date.now() / 1000) - fullGame.startedAt;
      const remaining = Math.max(0, config.checkinDurationSeconds - elapsed);
      if (remaining <= 0) {
        log.info({ gameId: game.gameId }, "Completing expired checkin on recovery");
        completeCheckin(game.gameId);
      } else {
        log.info({ gameId: game.gameId, remainingSeconds: remaining }, "Recovering checkin timer");
        const timer = setTimeout(() => {
          checkinTimers.delete(game.gameId);
          completeCheckin(game.gameId);
        }, remaining * 1000);
        checkinTimers.set(game.gameId, timer);
      }
    } else if (fullGame.subPhase === "pregame") {
      // Recover pregame timer — calculate remaining time
      const elapsed = Math.floor(Date.now() / 1000) - fullGame.startedAt;
      const checkinElapsed = config.checkinDurationSeconds;
      const pregameElapsed = elapsed - checkinElapsed;
      const remaining = Math.max(0, config.pregameDurationSeconds - pregameElapsed);
      if (remaining <= 0) {
        log.info({ gameId: game.gameId }, "Completing expired pregame on recovery");
        completePregame(game.gameId);
      } else {
        log.info({ gameId: game.gameId, remainingSeconds: remaining }, "Recovering pregame timer");
        const timer = setTimeout(() => {
          pregameTimers.delete(game.gameId);
          completePregame(game.gameId);
        }, remaining * 1000);
        pregameTimers.set(game.gameId, timer);
      }
    } else if (fullGame.subPhase === "game") {
      // Recover active game with zone tracker and tick
      log.info({ gameId: game.gameId }, "Recovering active game");
      const zoneTracker = ZoneTracker.fromDb(
        game.gameId,
        game.centerLat,
        game.centerLng,
        fullGame.startedAt
      );
      const tickInterval = setInterval(() => gameTick(game.gameId), 1000);
      activeGames.set(game.gameId, { zoneTracker, tickInterval });
    }
  }

  // Schedule deadline timers for registration-phase games
  const regGames = getGamesInPhase(GamePhase.REGISTRATION);
  for (const game of regGames) {
    scheduleDeadlineCheck(game);
  }

  log.info(
    { recoveredActive: activeDbGames.length, recoveredRegistration: regGames.length },
    "Games recovered"
  );
}

// ============ Cleanup ============

function cleanupActiveGame(gameId: number): void {
  const active = activeGames.get(gameId);
  if (active) {
    clearInterval(active.tickInterval);
    activeGames.delete(gameId);
  }
  const checkinTimer = checkinTimers.get(gameId);
  if (checkinTimer) {
    clearTimeout(checkinTimer);
    checkinTimers.delete(gameId);
  }
  const pregameTimer = pregameTimers.get(gameId);
  if (pregameTimer) {
    clearTimeout(pregameTimer);
    pregameTimers.delete(gameId);
  }
  log.info({ gameId }, "Active game cleaned up");
}

/**
 * Cleanup all active games and deadline timers (on shutdown).
 */
export function cleanupAll(): void {
  for (const [gameId, active] of activeGames) {
    clearInterval(active.tickInterval);
    log.info({ gameId }, "Cleaned up active game");
  }
  activeGames.clear();

  for (const [gameId, timer] of deadlineTimers) {
    clearTimeout(timer);
  }
  deadlineTimers.clear();

  for (const [gameId, timer] of checkinTimers) {
    clearTimeout(timer);
  }
  checkinTimers.clear();

  for (const [gameId, timer] of pregameTimers) {
    clearTimeout(timer);
  }
  pregameTimers.clear();
}

// ============ Query Helpers ============

/**
 * Get game status for REST API.
 */
export function getGameStatus(gameId: number) {
  const game = getGame(gameId);
  if (!game) return null;

  const players = getPlayers(gameId);
  const leaderboard = getLeaderboard(gameId);
  const active = activeGames.get(gameId);
  const zone = active?.zoneTracker.getZoneState() ?? null;

  return {
    gameId,
    phase: game.phase,
    subPhase: game.subPhase,
    checkinEndsAt: game.subPhase === "checkin" && game.startedAt
      ? game.startedAt + config.checkinDurationSeconds
      : null,
    pregameEndsAt: game.subPhase === "pregame" && game.startedAt
      ? game.startedAt + config.checkinDurationSeconds + config.pregameDurationSeconds
      : null,
    playerCount: players.length,
    aliveCount: players.filter((p) => p.isAlive).length,
    leaderboard,
    zone,
    winner1: game.winner1,
    winner2: game.winner2,
    winner3: game.winner3,
    topKiller: game.topKiller,
  };
}
