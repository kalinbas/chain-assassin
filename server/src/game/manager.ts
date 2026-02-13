import { config } from "../config.js";
import { createLogger } from "../utils/logger.js";
import { contractCoordToDegrees, haversineDistance } from "../utils/geo.js";
import { parseKillQrPayload } from "../utils/crypto.js";
import { GamePhase } from "../utils/types.js";
import type { GameConfig, ZoneShrink, Player } from "../utils/types.js";
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
  setPlayerClaimed,
  updatePlayerCount,
  updateTotalCollected,
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
  getZoneShrinks,
} from "../db/queries.js";
import { initializeTargetChain, processKill, removeFromChain, getChainMap } from "./targetChain.js";
import { ZoneTracker } from "./zoneTracker.js";
import { verifyKill } from "./killVerifier.js";
import { getLeaderboard, determineWinners } from "./leaderboard.js";
import { hasBleMatch, normalizeBluetoothId } from "./ble.js";
import * as operator from "../blockchain/operator.js";
import { fetchGameState } from "../blockchain/contract.js";
import { getHttpProvider } from "../blockchain/client.js";

const log = createLogger("gameManager");

/** Clear and delete a timer from a map. */
function clearMapTimer<K>(
  map: Map<K, ReturnType<typeof setTimeout> | ReturnType<typeof setInterval>>,
  key: K,
  clearFn: (t: ReturnType<typeof setTimeout> | ReturnType<typeof setInterval>) => void = clearTimeout
): void {
  const t = map.get(key);
  if (t) { clearFn(t); map.delete(key); }
}

// In-memory active game state
const activeGames = new Map<number, {
  zoneTracker: ZoneTracker;
  tickInterval: ReturnType<typeof setInterval>;
}>();

// Per-game deadline timers — fires at registrationDeadline to cancel if not enough players
const deadlineTimers = new Map<number, ReturnType<typeof setTimeout>>();

// Per-game gameDate timers — fires at gameDate to start the game
const gameDateTimers = new Map<number, ReturnType<typeof setTimeout>>();

// Per-game checkin monitors — periodically checks readiness / expiry while in checkin
const checkinTimers = new Map<number, ReturnType<typeof setInterval>>();

// Per-game pregame timers — fires when pregame countdown ends
const pregameTimers = new Map<number, ReturnType<typeof setTimeout>>();

// Per-game spectator position broadcast during checkin/pregame (before gameTick takes over)
const preGameSpectatorIntervals = new Map<number, ReturnType<typeof setInterval>>();

// Per-game auto-seed retry timers — retries every 60s until enough nearby players are seeded
const autoSeedTimers = new Map<number, ReturnType<typeof setInterval>>();

// Simulated game IDs — skip on-chain operator calls for these
const simulatedGames = new Set<number>();

// Hybrid simulated games — registered on-chain, simulated off-chain.
// endGame still goes on-chain for these (players paid real entry fees).
const hybridSimulatedGames = new Set<number>();

// Prevent duplicate expiry tx submissions while waiting for chain event processing.
const expiryTriggerInFlight = new Set<number>();

// Prevent duplicate cancellation tx submissions while waiting for chain event processing.
const cancellationTriggerInFlight = new Set<number>();

// Prevent concurrent endGame attempts for the same game.
const endingGames = new Set<number>();

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

function getRequiredCheckedInPlayers(game: Pick<GameConfig, "bps2nd" | "bps3rd">): number {
  let required = 1;
  if (game.bps2nd > 0) required += 1;
  if (game.bps3rd > 0) required += 1;
  return required;
}

export function getPregameEndsAt(subPhaseStartedAt: number | null): number | null {
  if (subPhaseStartedAt == null) return null;
  return subPhaseStartedAt + config.pregameDurationSeconds;
}

export function getPregameRemainingSeconds(subPhaseStartedAt: number | null, nowSec: number): number {
  if (subPhaseStartedAt == null) return config.pregameDurationSeconds;
  const endsAt = getPregameEndsAt(subPhaseStartedAt);
  if (endsAt == null) return config.pregameDurationSeconds;
  return Math.max(0, endsAt - nowSec);
}

function startAutoSeedLoop(gameId: number): void {
  clearMapTimer(autoSeedTimers, gameId, clearInterval);
  const alivePlayers = getPlayers(gameId).filter((p) => p.isAlive);
  const seedCount = Math.max(1, Math.ceil(alivePlayers.length * 0.05));

  tryAutoSeed(gameId, seedCount);
  const autoSeedInterval = setInterval(() => {
    tryAutoSeed(gameId, seedCount);
  }, 60_000);
  autoSeedTimers.set(gameId, autoSeedInterval);

  log.info({ gameId, seedCount }, "Auto-seed loop started");
}

function startCheckinMonitor(gameId: number): void {
  clearMapTimer(checkinTimers, gameId, clearInterval);
  const interval = setInterval(() => {
    void evaluateCheckinState(gameId);
  }, 2_000);
  checkinTimers.set(gameId, interval);
}

async function evaluateCheckinState(gameId: number): Promise<void> {
  const game = getGame(gameId);
  if (!game || game.phase !== GamePhase.ACTIVE || game.subPhase !== "checkin") {
    clearMapTimer(checkinTimers, gameId, clearInterval);
    return;
  }

  const checkedInCount = getCheckedInCount(gameId);
  const requiredCheckedIn = getRequiredCheckedInPlayers(game);

  if (checkedInCount >= requiredCheckedIn) {
    clearMapTimer(checkinTimers, gameId, clearInterval);
    completeCheckin(gameId);
    return;
  }

  let now = Math.floor(Date.now() / 1000);
  try {
    const latest = await getHttpProvider().getBlock("latest");
    if (latest) now = latest.timestamp;
  } catch (err) {
    log.warn({ gameId, error: (err as Error).message }, "Falling back to wall-clock time for checkin expiry");
  }
  if (now <= game.expiryDeadline) return;

  clearMapTimer(autoSeedTimers, gameId, clearInterval);

  if (isSimulatedGame(gameId) && !isHybridSimulatedGame(gameId)) {
    log.info({ gameId }, "Pure simulated game expired during checkin; cancelling off-chain");
    onGameCancelled(gameId);
    return;
  }

  if (expiryTriggerInFlight.has(gameId)) return;
  expiryTriggerInFlight.add(gameId);

  try {
    log.info(
      { gameId, checkedInCount, requiredCheckedIn, expiryDeadline: game.expiryDeadline },
      "Checkin requirements not met by expiry; triggering on-chain expiry cancellation"
    );
    await operator.triggerExpiry(gameId);
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Failed to trigger expiry cancellation");
  } finally {
    expiryTriggerInFlight.delete(gameId);
  }
}

// ============ Game Lifecycle ============

/**
 * Handle a new game created on-chain.
 */
export function onGameCreated(gameConfig: GameConfig, shrinks: ZoneShrink[]): void {
  insertGame(gameConfig);
  insertZoneShrinks(gameConfig.gameId, shrinks);
  scheduleTimers(gameConfig);
  log.info({ gameId: gameConfig.gameId, title: gameConfig.title }, "Game tracked");
}

/**
 * Handle a player registration event from the chain.
 */
export async function onPlayerRegistered(gameId: number, playerAddress: string, playerCount: number): Promise<void> {
  insertPlayer(gameId, playerAddress, playerCount);
  updatePlayerCount(gameId, playerCount);

  // Fetch totalCollected from chain (increases with each registration as entry fees accumulate)
  try {
    const state = await fetchGameState(gameId);
    updateTotalCollected(gameId, state.totalCollected.toString());
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Failed to fetch totalCollected after registration");
  }

  log.info({ gameId, player: playerAddress, playerCount }, "Player registered");

  broadcast(gameId, {
    type: "player:registered",
    playerNumber: playerCount, // playerCount == new player's number (sequential)
    playerCount,
  });
}

/**
 * Try to auto-seed nearby players as checked in.
 * Only seeds players within 5km of the meeting point who aren't already checked in.
 * Stops the retry interval once seedCount is reached or checkin phase ends.
 */
function tryAutoSeed(gameId: number, seedCount: number): void {
  const checkedIn = getCheckedInCount(gameId);
  if (checkedIn >= seedCount) {
    clearMapTimer(autoSeedTimers, gameId, clearInterval);
    log.info({ gameId, checkedIn, seedCount }, "Auto-seed: target reached, stopping retries");
    return;
  }

  const game = getGame(gameId);
  if (!game || game.subPhase !== "checkin") {
    clearMapTimer(autoSeedTimers, gameId, clearInterval);
    return;
  }

  const meetLat = game.meetingLat !== 0
    ? contractCoordToDegrees(game.meetingLat) : contractCoordToDegrees(game.centerLat);
  const meetLng = game.meetingLng !== 0
    ? contractCoordToDegrees(game.meetingLng) : contractCoordToDegrees(game.centerLng);

  const players = getPlayers(gameId).filter((p) => p.isAlive && !p.checkedIn);
  const remaining = seedCount - checkedIn;

  const nearby: { player: Player; distance: number }[] = [];
  for (const p of players) {
    const ping = getLatestLocationPing(gameId, p.address);
    if (ping) {
      const dist = haversineDistance(ping.lat, ping.lng, meetLat, meetLng);
      if (dist <= 5000) nearby.push({ player: p, distance: dist });
    }
  }
  nearby.sort((a, b) => a.distance - b.distance);
  const seeds = nearby.slice(0, remaining);

  for (const { player: seed } of seeds) {
    setPlayerCheckedIn(gameId, seed.address);
    broadcast(gameId, {
      type: "checkin:update",
      checkedInCount: getCheckedInCount(gameId),
      totalPlayers: getPlayerCount(gameId),
      playerNumber: seed.playerNumber,
    });
    log.info({ gameId, address: seed.address }, "Auto-seeded check-in");
  }

  if (seeds.length > 0) {
    log.info({ gameId, seeded: seeds.length, remaining: remaining - seeds.length }, "Auto-seed: seeded nearby players");
  } else {
    log.info({ gameId, remaining }, "Auto-seed: no nearby players found, will retry in 60s");
  }

  if (getCheckedInCount(gameId) >= seedCount) {
    clearMapTimer(autoSeedTimers, gameId, clearInterval);
    log.info({ gameId }, "Auto-seed: target reached after seeding");
  }
}

/**
 * Start a game — called after operator.startGame() tx confirms.
 * Transitions to ACTIVE phase with sub_phase='checkin'.
 * Check-in remains open until enough players are checked in for prize slots,
 * or until game expiry is reached.
 */
export function onGameStarted(gameId: number): void {
  cancelTimers(gameId);

  const game = getGame(gameId);
  if (!game) {
    log.error({ gameId }, "Game not found for start");
    return;
  }

  const now = Math.floor(Date.now() / 1000);

  // Set phase to ACTIVE with sub_phase='checkin'
  updateGamePhase(gameId, GamePhase.ACTIVE, {
    startedAt: now,
    subPhase: "checkin",
    subPhaseStartedAt: now,
  });

  const requiredCheckedIn = getRequiredCheckedInPlayers(game);

  broadcast(gameId, {
    type: "game:checkin_started",
    checkinDurationSeconds: Math.max(0, game.expiryDeadline - now),
    checkinEndsAt: game.expiryDeadline,
    requiredCheckedIn,
  });

  // Auto-seed: check in players near the meeting point (5% of total, min 1)
  // Retries every 60s until enough nearby players are seeded or normal check-ins fill the gap
  startAutoSeedLoop(gameId);

  // Start spectator position broadcast during pre-game phases
  startPreGameSpectatorBroadcast(gameId);

  // Keep checking whether checkin requirements are met (or expiry reached)
  startCheckinMonitor(gameId);
  void evaluateCheckinState(gameId);

  log.info(
    { gameId, requiredCheckedIn, checkinEndsAt: game.expiryDeadline },
    "Check-in phase started"
  );
}

/**
 * Complete the check-in phase — eliminate non-checked-in players, then transition to pregame.
 */
function completeCheckin(gameId: number): void {
  // Stop auto-seed retries
  clearMapTimer(autoSeedTimers, gameId, clearInterval);
  clearMapTimer(checkinTimers, gameId, clearInterval);

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
        playerNumber: player.playerNumber,
        eliminatorNumber: 0,
        reason: "no_checkin",
      });
    }
  }

  // Check if any players remain after elimination
  const aliveCount = getAlivePlayerCount(gameId);
  if (aliveCount === 0) {
    log.warn({ gameId }, "No players remained alive after checkin completion");
    return;
  }

  // Single-player game can end immediately (no possible kill flow).
  if (aliveCount === 1) {
    void endGameWithWinners(gameId);
    return;
  }

  const pregameNow = Math.floor(Date.now() / 1000);
  const pregameEndsAt = getPregameEndsAt(pregameNow);

  // Transition to pregame and persist when this sub-phase started.
  updateSubPhase(gameId, "pregame", pregameNow);

  const checkedInCount = getCheckedInCount(gameId);

  broadcast(gameId, {
    type: "game:pregame_started",
    pregameDurationSeconds: config.pregameDurationSeconds,
    pregameEndsAt,
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

  // Stop pre-game spectator broadcast (gameTick will take over)
  stopPreGameSpectatorBroadcast(gameId);

  // Get alive (checked-in) players only
  const alivePlayers = getAlivePlayers(gameId);
  const addresses = alivePlayers.map((p) => p.address);

  if (addresses.length <= 1) {
    void endGameWithWinners(gameId);
    return;
  }

  // Initialize target chain
  const chain = initializeTargetChain(gameId, addresses);

  // Initialize zone tracker
  const zoneTrackerFull = ZoneTracker.fromDb(gameId, game.centerLat, game.centerLng, game.startedAt ?? now);

  // Start game tick (1 second interval)
  const tickInterval = setInterval(() => gameTick(gameId), 1000);

  activeGames.set(gameId, { zoneTracker: zoneTrackerFull, tickInterval });

  // Initialize heartbeats for alive players
  initPlayersHeartbeat(gameId, now);

  // Persist transition to in-game sub-phase only after setup succeeds.
  updateSubPhase(gameId, "game", now);

  // Notify all players of their targets + hunter number
  for (const [hunter, target] of chain) {
    const targetPlayer = getPlayer(gameId, target);
    const hunterOfHunter = findHunterOf(gameId, hunter);
    const hunterOfHunterPlayer = hunterOfHunter ? getPlayer(gameId, hunterOfHunter) : null;
    sendToPlayer(gameId, hunter, {
      type: "game:started",
      target: {
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

  // Get updated hunter & target stats
  const hunter = getPlayer(gameId, hunterAddress);
  const target = getPlayer(gameId, targetAddress);

  // Submit on-chain (fire and forget — don't block the response)
  if (!isSimulatedGame(gameId)) {
    const hunterNum = hunter?.playerNumber ?? 0;
    const targetNum = target?.playerNumber ?? 0;
    if (hunterNum > 0 && targetNum > 0) {
      operator
        .recordKill(gameId, hunterNum, targetNum)
        .then((tx) => {
          updateKillTxHash(killId, tx.txHash);
        })
        .catch((err) => {
          log.error({ gameId, killId, error: err.message }, "Failed to record kill on-chain");
        });
    }
  }

  // Broadcast kill event
  broadcast(gameId, {
    type: "kill:recorded",
    hunterNumber: hunter?.playerNumber ?? 0,
    targetNumber: target?.playerNumber ?? 0,
    hunterKills: hunter?.kills ?? 0,
  });

  // Broadcast elimination
  broadcast(gameId, {
    type: "player:eliminated",
    playerNumber: target?.playerNumber ?? 0,
    eliminatorNumber: hunter?.playerNumber ?? 0,
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
): { success: boolean; error?: string } {
  const game = getGame(gameId);
  if (!game) return { success: false, error: "Game not found" };

  const player = getPlayer(gameId, address);
  if (!player) return { success: false, error: "Not registered" };
  if (!player.isAlive) return { success: false, error: "Player is eliminated" };

  const now = Math.floor(Date.now() / 1000);
  const active = activeGames.get(gameId);

  if (!active) {
    // During checkin/pregame, still store the location (no zone tracking yet)
    insertLocationPing({
      gameId,
      address,
      lat,
      lng,
      timestamp: now,
      isInZone: true,
    });
    return { success: true };
  }

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

  return { success: true };
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
  bluetoothId?: string,
  bleNearbyAddresses: string[] = []
): { success: boolean; error?: string } {
  const game = getGame(gameId);
  if (!game) return { success: false, error: "Game not found" };

  // Only allow check-ins during ACTIVE/checkin sub-phase
  if (game.phase !== GamePhase.ACTIVE || game.subPhase !== "checkin") {
    return { success: false, error: "Check-in period has ended" };
  }

  const now = Math.floor(Date.now() / 1000);
  if (now > game.expiryDeadline) {
    return { success: false, error: "Game has expired" };
  }

  const player = getPlayer(gameId, address);
  if (!player) return { success: false, error: "Not registered" };

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

  const normalizedBluetoothId = normalizeBluetoothId(bluetoothId);

  // Auto-seeded players can finalize their Bluetooth ID without scanning.
  if (player.checkedIn) {
    if (!normalizedBluetoothId) return { success: false, error: "Already checked in" };
    setPlayerCheckedIn(gameId, address, normalizedBluetoothId);
    return { success: true };
  }

  // Viral check-in: scan a checked-in player's QR code (seed players are auto-checked-in by server)
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

  if (config.bleRequired) {
    const scannedBluetoothId = normalizeBluetoothId(scannedPlayer.bluetoothId);
    if (!scannedBluetoothId) {
      return {
        success: false,
        error: "Scanned player must finish Bluetooth check-in first",
      };
    }
    if (!hasBleMatch(scannedBluetoothId, bleNearbyAddresses)) {
      return {
        success: false,
        error: "Scanned player not detected via Bluetooth",
      };
    }
  }

  setPlayerCheckedIn(gameId, address, normalizedBluetoothId ?? undefined);
  const checkedIn = getCheckedInCount(gameId);
  const total = getPlayerCount(gameId);
  const checkedInPlayer = getPlayer(gameId, address);

  broadcast(gameId, {
    type: "checkin:update",
    checkedInCount: checkedIn,
    totalPlayers: total,
    playerNumber: checkedInPlayer?.playerNumber ?? 0,
  });

  return { success: true };
}

// ============ Pre-Game Spectator Broadcast ============

/**
 * Broadcast player positions to spectators during checkin/pregame phases.
 * Stops when gameTick takes over in the 'game' sub-phase.
 */
function startPreGameSpectatorBroadcast(gameId: number): void {
  stopPreGameSpectatorBroadcast(gameId);

  // Pre-compute zone info (no ZoneTracker yet, use DB data)
  const game = getGame(gameId);
  const shrinks = getZoneShrinks(gameId);
  const initialRadius = shrinks[0]?.radiusMeters ?? 500;
  const zone = game?.centerLat && game?.centerLng
    ? {
        centerLat: contractCoordToDegrees(game.centerLat),
        centerLng: contractCoordToDegrees(game.centerLng),
        currentRadiusMeters: initialRadius,
        nextShrinkAt: null,
        nextRadiusMeters: null,
      }
    : null;

  const interval = setInterval(() => {
    if (!spectatorBroadcastFn) return;

    const alivePlayers = getAlivePlayers(gameId);
    const positions = alivePlayers.map((p) => {
      const ping = getLatestLocationPing(gameId, p.address);
      return {
        playerNumber: p.playerNumber,
        lat: ping?.lat ?? null,
        lng: ping?.lng ?? null,
        isAlive: true,
        kills: p.kills,
      };
    });

    spectatorBroadcastFn(gameId, {
      type: "spectator:positions",
      players: positions,
      zone,
      aliveCount: alivePlayers.length,
      huntLinks: [],
    });
  }, 2000);

  preGameSpectatorIntervals.set(gameId, interval);
}

function stopPreGameSpectatorBroadcast(gameId: number): void {
  const existing = preGameSpectatorIntervals.get(gameId);
  if (existing) {
    clearInterval(existing);
    preGameSpectatorIntervals.delete(gameId);
  }
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

  const aliveAfterChecks = getAlivePlayerCount(gameId);
  if (aliveAfterChecks <= 1) {
    await endGameWithWinners(gameId);
    return;
  }

  // Broadcast player positions to spectators every 2 seconds
  if (now % 2 === 0 && spectatorBroadcastFn) {
    const alivePlayers = getAlivePlayers(gameId);
    const positions = alivePlayers.map((p) => {
      const ping = getLatestLocationPing(gameId, p.address);
      return {
        playerNumber: p.playerNumber,
        lat: ping?.lat ?? null,
        lng: ping?.lng ?? null,
        isAlive: true,
        kills: p.kills,
      };
    });

    // Build hunt links from target chain (using player numbers)
    const chainMap = getChainMap(gameId);
    const huntLinks: { hunter: number; target: number }[] = [];
    for (const [hunterAddr, targetAddr] of chainMap) {
      const h = getPlayer(gameId, hunterAddr);
      const t = getPlayer(gameId, targetAddr);
      if (h && t) huntLinks.push({ hunter: h.playerNumber, target: t.playerNumber });
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
 * Shared post-elimination logic: on-chain call, broadcasts, target reassignment, game-end check.
 * Used by both zone and heartbeat elimination paths.
 */
async function eliminateAndReassign(
  gameId: number,
  address: string,
  player: Player,
  reason: string,
  reassignment: { reassignedHunter: string; newTarget: string } | null
): Promise<void> {
  // Clear zone tracking
  const active = activeGames.get(gameId);
  active?.zoneTracker.clearPlayer(address);

  // Submit on-chain
  if (!isSimulatedGame(gameId)) {
    const pNum = player.playerNumber;
    if (pNum > 0) {
      operator.eliminatePlayer(gameId, pNum).catch((err) => {
        log.error({ gameId, address, error: err.message }, `Failed to eliminate player on-chain (${reason})`);
      });
    }
  }

  // Broadcast elimination
  broadcast(gameId, {
    type: "player:eliminated",
    playerNumber: player.playerNumber,
    eliminatorNumber: 0,
    reason,
  });

  // Notify reassigned hunter of new target + their updated hunter number
  if (reassignment) {
    const newTargetPlayer = getPlayer(gameId, reassignment.newTarget);
    const hunterOfReassigned = findHunterOf(gameId, reassignment.reassignedHunter);
    const hunterOfReassignedPlayer = hunterOfReassigned ? getPlayer(gameId, hunterOfReassigned) : null;
    sendToPlayer(gameId, reassignment.reassignedHunter, {
      type: "target:assigned",
      target: {
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
 * Eliminate a player for being out of zone too long.
 */
async function handleZoneElimination(gameId: number, address: string): Promise<void> {
  const player = getPlayer(gameId, address);
  if (!player || !player.isAlive) {
    const active = activeGames.get(gameId);
    active?.zoneTracker.clearPlayer(address);
    return;
  }

  log.info({ gameId, address }, "Eliminating player for zone violation");
  const now = Math.floor(Date.now() / 1000);
  dbEliminatePlayer(gameId, address, "zone", now);
  const reassignment = removeFromChain(gameId, address);
  await eliminateAndReassign(gameId, address, player, "zone_violation", reassignment);
}

/**
 * Eliminate a player for missing their heartbeat scan deadline.
 */
async function handleHeartbeatElimination(gameId: number, address: string): Promise<void> {
  const player = getPlayer(gameId, address);
  if (!player || !player.isAlive) return;

  log.info({ gameId, address, playerNumber: player.playerNumber }, "Eliminating player for missed heartbeat");
  const now = Math.floor(Date.now() / 1000);
  dbEliminatePlayer(gameId, address, "heartbeat", now);
  const reassignment = removeFromChain(gameId, address);
  await eliminateAndReassign(gameId, address, player, "heartbeat_timeout", reassignment);
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
    const scannedBluetoothId = normalizeBluetoothId(scannedPlayer.bluetoothId);
    if (!scannedBluetoothId) {
      log.warn(
        { gameId, scanned: scannedPlayer.address },
        "Scanned player has no bluetooth_id; skipping heartbeat BLE check"
      );
    } else if (!hasBleMatch(scannedBluetoothId, bleNearbyAddresses)) {
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
  if (endingGames.has(gameId)) return;

  const game = getGame(gameId);
  if (!game) return;
  if (game.phase !== GamePhase.ACTIVE) return;

  endingGames.add(gameId);

  try {
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

    // Resolve addresses to playerNumbers for on-chain call
    const ZERO = "0x0000000000000000000000000000000000000000";
    const w1Player = winner1 !== ZERO ? getPlayer(gameId, winner1) : null;
    const w2Player = winner2 !== ZERO ? getPlayer(gameId, winner2) : null;
    const w3Player = winner3 !== ZERO ? getPlayer(gameId, winner3) : null;
    const tkPlayer = topKiller !== ZERO ? getPlayer(gameId, topKiller) : null;

    const w1Num = w1Player?.playerNumber ?? 0;
    const w2Num = w2Player?.playerNumber ?? 0;
    const w3Num = w3Player?.playerNumber ?? 0;
    const tkNum = tkPlayer?.playerNumber ?? 0;

    if (!isSimulatedGame(gameId) || isHybridSimulatedGame(gameId)) {
      await operator.endGame(gameId, w1Num, w2Num, w3Num, tkNum);
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
      winner1: w1Num,
      winner2: w2Num,
      winner3: w3Num,
      topKiller: tkNum,
    });

    cleanupActiveGame(gameId);
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Failed to end game on-chain");
  } finally {
    endingGames.delete(gameId);
  }
}

/**
 * Handle a game cancelled event.
 */
export function onGameCancelled(gameId: number): void {
  cancelTimers(gameId);
  updateGamePhase(gameId, GamePhase.CANCELLED, { endedAt: Math.floor(Date.now() / 1000) });
  broadcast(gameId, { type: "game:cancelled", gameId });
  cleanupActiveGame(gameId);
  log.info({ gameId }, "Game cancelled");
}

/**
 * Handle game ended event from chain (confirmation).
 * Winners arrive as playerNumbers (uint16) from the contract events.
 * Resolve to addresses for DB storage.
 */
export function onGameEnded(
  gameId: number,
  winner1: number,
  winner2: number,
  winner3: number,
  topKiller: number
): void {
  const now = Math.floor(Date.now() / 1000);

  // Resolve playerNumbers to addresses via DB
  const resolveAddr = (pNum: number): string => {
    if (pNum === 0) return "";
    const player = getPlayerByNumber(gameId, pNum);
    return player?.address ?? "";
  };

  const winner1Addr = resolveAddr(winner1);
  const winner2Addr = resolveAddr(winner2);
  const winner3Addr = resolveAddr(winner3);
  const topKillerAddr = resolveAddr(topKiller);

  updateGamePhase(gameId, GamePhase.ENDED, {
    endedAt: now,
    winner1: winner1Addr,
    winner2: winner2Addr,
    winner3: winner3Addr,
    topKiller: topKillerAddr,
  });
  cleanupActiveGame(gameId);
  log.info({ gameId, winner1, winner2, winner3, topKiller }, "Game ended (chain confirmed)");
}

/**
 * Handle a prize claimed event from the chain.
 */
export function onPrizeClaimed(gameId: number, address: string): void {
  setPlayerClaimed(gameId, address);
  log.info({ gameId, address }, "Prize claimed");
}

/**
 * Handle a refund claimed event from the chain.
 */
export function onRefundClaimed(gameId: number, address: string): void {
  setPlayerClaimed(gameId, address);
  log.info({ gameId, address }, "Refund claimed");
}

// ============ Scheduling ============

/**
 * Schedule both timers for a registration-phase game:
 * 1. Deadline check — fires at registrationDeadline: cancel if not enough players
 * 2. Game date check — fires at gameDate: start if enough players
 */
export function scheduleTimers(config: GameConfig): void {
  cancelTimers(config.gameId);

  const nowSec = Math.floor(Date.now() / 1000);

  // Timer 1: fires at registrationDeadline — cancel game if < minPlayers
  const deadlineDelay = Math.max(0, config.registrationDeadline - nowSec + 2);
  const deadlineTimer = setTimeout(async () => {
    deadlineTimers.delete(config.gameId);
    try {
      await checkDeadline(config.gameId);
    } catch (err) {
      log.error({ gameId: config.gameId, error: (err as Error).message }, "Deadline check failed");
    }
  }, deadlineDelay * 1000);
  deadlineTimers.set(config.gameId, deadlineTimer);
  log.info({ gameId: config.gameId, delaySec: deadlineDelay }, "Deadline check scheduled");

  // Timer 2: fires at gameDate — start game if >= minPlayers
  const gameDateDelay = Math.max(0, config.gameDate - nowSec + 2);
  const gameDateTimer = setTimeout(async () => {
    gameDateTimers.delete(config.gameId);
    try {
      await checkGameDate(config.gameId);
    } catch (err) {
      log.error({ gameId: config.gameId, error: (err as Error).message }, "Game date check failed");
    }
  }, gameDateDelay * 1000);
  gameDateTimers.set(config.gameId, gameDateTimer);
  log.info({ gameId: config.gameId, delaySec: gameDateDelay }, "Game date check scheduled");
}

/**
 * Cancel all pending timers for a game.
 */
export function cancelTimers(gameId: number): void {
  clearMapTimer(deadlineTimers, gameId);
  clearMapTimer(gameDateTimers, gameId);
}

// ============ Deadline Check (registrationDeadline) ============

async function triggerCancellationIfNeeded(gameId: number, reason: "deadline" | "gameDate"): Promise<void> {
  if (cancellationTriggerInFlight.has(gameId)) {
    log.info({ gameId, reason }, "Skipping duplicate cancellation trigger (already in-flight)");
    return;
  }

  const game = getGame(gameId);
  if (!game || game.phase !== GamePhase.REGISTRATION) return;

  // Pure simulated games have no on-chain phase to cancel.
  if (isSimulatedGame(gameId) && !isHybridSimulatedGame(gameId)) {
    log.info({ gameId, reason }, "Cancelling simulated registration game off-chain");
    onGameCancelled(gameId);
    return;
  }

  cancellationTriggerInFlight.add(gameId);
  try {
    // Guard against async races: if another actor already cancelled/started on-chain,
    // do not enqueue a stale operator cancellation tx.
    const state = await fetchGameState(gameId);
    if (state.phase !== GamePhase.REGISTRATION) {
      log.info({ gameId, reason, onChainPhase: state.phase }, "Skipping cancellation: on-chain phase is no longer registration");
      return;
    }
    await operator.triggerCancellation(gameId);
  } finally {
    cancellationTriggerInFlight.delete(gameId);
  }
}

/**
 * Fires at registrationDeadline. If not enough players registered,
 * cancel the game on-chain immediately.
 */
async function checkDeadline(gameId: number): Promise<void> {
  const game = getGame(gameId);
  if (!game || game.phase !== GamePhase.REGISTRATION) return;

  const playerCount = getPlayerCount(gameId);
  if (playerCount >= game.minPlayers) {
    log.info({ gameId, playerCount, minPlayers: game.minPlayers }, "Enough players at deadline, waiting for game date");
    return;
  }

  log.info({ gameId, playerCount, minPlayers: game.minPlayers }, "Not enough players at deadline, cancelling game");
  try {
    await triggerCancellationIfNeeded(gameId, "deadline");
    // onGameCancelled will be called from the event listener when tx confirms
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Failed to cancel game (not enough players)");
  }
}

// ============ Game Date Check (gameDate) ============

/**
 * Fires at gameDate. If the game is still in REGISTRATION (wasn't cancelled
 * at deadline) and has enough players, start it on-chain.
 */
async function checkGameDate(gameId: number): Promise<void> {
  const game = getGame(gameId);
  if (!game || game.phase !== GamePhase.REGISTRATION) return;

  const playerCount = getPlayerCount(gameId);
  if (playerCount < game.minPlayers) {
    log.info({ gameId, playerCount, minPlayers: game.minPlayers }, "Not enough players at game date, cancelling");
    try {
      await triggerCancellationIfNeeded(gameId, "gameDate");
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Failed to cancel game at game date");
    }
    return;
  }

  log.info({ gameId, playerCount }, "Auto-starting game (game date reached)");
  try {
    await operator.startGame(gameId);
    // onGameStarted will be called from the event listener when tx confirms
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Failed to auto-start game");
  }
}

// ============ Admin: force check all registration games ============

/**
 * Admin endpoint: run deadline + game date checks on all registration games.
 */
export async function checkAllRegistrationGames(): Promise<void> {
  const regGames = getGamesInPhase(GamePhase.REGISTRATION);
  let nowSec = Math.floor(Date.now() / 1000);
  try {
    const latest = await getHttpProvider().getBlock("latest");
    if (latest) nowSec = latest.timestamp;
  } catch (err) {
    log.warn({ error: (err as Error).message }, "Falling back to wall-clock time for admin registration checks");
  }

  for (const game of regGames) {
    // Avoid duplicate cancellation submissions: if deadline is passed and the game
    // is still below min players, only run deadline cancellation logic.
    if (nowSec >= game.registrationDeadline && getPlayerCount(game.gameId) < game.minPlayers) {
      await checkDeadline(game.gameId);
      continue;
    }

    if (nowSec >= game.gameDate) {
      await checkGameDate(game.gameId);
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
      log.info({ gameId: game.gameId }, "Recovering checkin monitor");
      startPreGameSpectatorBroadcast(game.gameId);
      startAutoSeedLoop(game.gameId);
      startCheckinMonitor(game.gameId);
      void evaluateCheckinState(game.gameId);
    } else if (fullGame.subPhase === "pregame") {
      const nowSec = Math.floor(Date.now() / 1000);
      const remainingSeconds = getPregameRemainingSeconds(fullGame.subPhaseStartedAt, nowSec);
      log.info({ gameId: game.gameId, remainingSeconds }, "Recovering pregame timer");
      startPreGameSpectatorBroadcast(game.gameId);

      if (remainingSeconds <= 0) {
        completePregame(game.gameId);
        continue;
      }

      const timer = setTimeout(() => {
        pregameTimers.delete(game.gameId);
        completePregame(game.gameId);
      }, remainingSeconds * 1000);
      pregameTimers.set(game.gameId, timer);
    } else if (fullGame.subPhase === "game") {
      // Recover active game with zone tracker and tick
      log.info({ gameId: game.gameId }, "Recovering active game");
      const zoneTracker = ZoneTracker.fromDb(
        game.gameId,
        game.centerLat,
        game.centerLng,
        fullGame.startedAt
      );

      // Rehydrate in-memory out-of-zone state from latest persisted pings so
      // grace countdowns continue correctly after a crash/restart.
      const alivePlayers = getAlivePlayers(game.gameId);
      for (const player of alivePlayers) {
        const ping = getLatestLocationPing(game.gameId, player.address);
        if (!ping) continue;
        zoneTracker.processLocation(player.address, ping.lat, ping.lng, ping.timestamp);
      }

      const tickInterval = setInterval(() => gameTick(game.gameId), 1000);
      activeGames.set(game.gameId, { zoneTracker, tickInterval });
      void gameTick(game.gameId);
    }
  }

  // Schedule deadline timers for registration-phase games
  const regGames = getGamesInPhase(GamePhase.REGISTRATION);
  for (const game of regGames) {
    scheduleTimers(game);
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
  stopPreGameSpectatorBroadcast(gameId);
  clearMapTimer(checkinTimers, gameId, clearInterval);
  clearMapTimer(pregameTimers, gameId);
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

  for (const [gameId, timer] of gameDateTimers) {
    clearTimeout(timer);
  }
  gameDateTimers.clear();

  for (const [gameId, timer] of checkinTimers) {
    clearInterval(timer);
  }
  checkinTimers.clear();

  for (const [gameId, timer] of pregameTimers) {
    clearTimeout(timer);
  }
  pregameTimers.clear();

  for (const [gameId, interval] of preGameSpectatorIntervals) {
    clearInterval(interval);
  }
  preGameSpectatorIntervals.clear();

  for (const [gameId, interval] of autoSeedTimers) {
    clearInterval(interval);
  }
  autoSeedTimers.clear();
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
  let zone = active?.zoneTracker.getZoneState() ?? null;
  if (!zone && game.phase === GamePhase.ACTIVE && game.centerLat && game.centerLng) {
    const shrinks = getZoneShrinks(gameId);
    const initialRadius = shrinks[0]?.radiusMeters ?? 500;
    zone = {
      centerLat: contractCoordToDegrees(game.centerLat),
      centerLng: contractCoordToDegrees(game.centerLng),
      currentRadiusMeters: initialRadius,
      nextShrinkAt: null,
      nextRadiusMeters: null,
    };
  }

  return {
    gameId,
    phase: game.phase,
    subPhase: game.subPhase,
    checkinEndsAt: game.subPhase === "checkin"
      ? game.expiryDeadline
      : null,
    pregameEndsAt: game.subPhase === "pregame"
      ? getPregameEndsAt(game.subPhaseStartedAt)
      : null,
    playerCount: players.length,
    aliveCount: players.filter((p) => p.isAlive).length,
    checkedInCount: players.filter((p) => p.checkedIn).length,
    leaderboard,
    zone,
    winner1: game.winner1 ? getPlayer(gameId, game.winner1)?.playerNumber ?? 0 : 0,
    winner2: game.winner2 ? getPlayer(gameId, game.winner2)?.playerNumber ?? 0 : 0,
    winner3: game.winner3 ? getPlayer(gameId, game.winner3)?.playerNumber ?? 0 : 0,
    topKiller: game.topKiller ? getPlayer(gameId, game.topKiller)?.playerNumber ?? 0 : 0,
  };
}
