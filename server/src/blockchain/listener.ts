import { ethers, ContractEventPayload } from "ethers";
import {
  getWsContract,
  fetchGameConfig,
  fetchGameState,
  fetchZoneShrinks,
  fetchNextGameId,
  fetchPlayer,
  resetWsContract,
} from "./contract.js";
import { getHttpProvider, getWsProvider, resetWsProvider } from "./client.js";
import { config } from "../config.js";
import {
  getSyncState,
  setSyncState,
  resetGameData,
  getGame,
  getPlayerByNumber,
  getZoneShrinks as getDbZoneShrinks,
  insertGame,
  insertPlayer,
  insertZoneShrinks,
  updateGamePhase,
  updateTotalCollected,
  updatePlayerCount,
} from "../db/queries.js";
import * as manager from "../game/manager.js";
import { GamePhase } from "../utils/types.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("listener");
const ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
const WS_READY_STATE_OPEN = 1;

const SYNC_KEY_LAST_BLOCK = "lastProcessedBlock";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let listenerRunning = false;
let listenerRestarting = false;
let lastHeartbeatAtMs = 0;
let lastHeartbeatBlock = 0;
let lastRestartAtMs = 0;
let restartCount = 0;
let lastRestartReason: string | null = null;
let lastRestartError: string | null = null;
let heartbeatCheckTimer: NodeJS.Timeout | null = null;
let restartRetryTimer: NodeJS.Timeout | null = null;
let wsBlockHandler: ((blockNumber: number) => void) | null = null;
let wsProviderForListener: ethers.WebSocketProvider | null = null;

export interface EventListenerStatus {
  running: boolean;
  restarting: boolean;
  wsReadyState: number | null;
  heartbeatMonitorActive: boolean;
  lastHeartbeatAtMs: number | null;
  lastHeartbeatBlock: number | null;
  heartbeatAgeMs: number | null;
  lastRestartAtMs: number | null;
  restartCount: number;
  lastRestartReason: string | null;
  lastRestartError: string | null;
  lastProcessedBlock: number | null;
}

interface ResolvedPlayerState {
  address: string;
  killedAt: number;
  claimed: boolean;
  killCount: number;
}

async function fetchPlayerStateWithRetry(
  gameId: number,
  playerNumber: number,
  maxAttempts = 8,
  retryDelayMs = 250
): Promise<ResolvedPlayerState> {
  let lastError: Error | null = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const playerState = await fetchPlayer(gameId, playerNumber);
      const address = playerState.addr.toLowerCase();
      if (address !== ZERO_ADDRESS) {
        return {
          address,
          killedAt: playerState.killedAt,
          claimed: playerState.claimed,
          killCount: playerState.killCount,
        };
      }
      lastError = new Error(`Player ${playerNumber} resolved to zero address`);
    } catch (err) {
      lastError = err as Error;
    }

    if (attempt < maxAttempts) {
      await sleep(retryDelayMs * attempt);
    }
  }

  throw new Error(
    `Failed to resolve on-chain player ${playerNumber}: ${lastError?.message ?? "unknown error"}`
  );
}

async function fetchPlayerAddressWithRetry(
  gameId: number,
  playerNumber: number,
  maxAttempts = 8,
  retryDelayMs = 250
): Promise<string> {
  return (await fetchPlayerStateWithRetry(gameId, playerNumber, maxAttempts, retryDelayMs)).address;
}

function touchWsHeartbeat(blockNumber?: number): void {
  lastHeartbeatAtMs = Date.now();
  if (blockNumber != null) {
    lastHeartbeatBlock = blockNumber;
  }
}

function getWsReadyState(): number | undefined {
  const ws = wsProviderForListener?.websocket as { readyState?: number } | undefined;
  return ws?.readyState;
}

function startHeartbeatMonitor(): void {
  if (heartbeatCheckTimer) {
    clearInterval(heartbeatCheckTimer);
  }

  heartbeatCheckTimer = setInterval(() => {
    if (!listenerRunning || listenerRestarting) return;

    const now = Date.now();
    const heartbeatAgeMs = now - lastHeartbeatAtMs;
    const readyState = getWsReadyState();
    let reason: string | null = null;

    if (readyState !== undefined && readyState !== WS_READY_STATE_OPEN) {
      reason = `websocket_not_open:${readyState}`;
    } else if (heartbeatAgeMs > config.wsHeartbeatStaleMs) {
      reason = `heartbeat_stale:${heartbeatAgeMs}ms`;
    }

    if (reason) {
      void restartEventListener(reason);
    }
  }, config.wsHeartbeatCheckIntervalMs);

  if (typeof heartbeatCheckTimer.unref === "function") {
    heartbeatCheckTimer.unref();
  }
}

function stopHeartbeatMonitor(): void {
  if (heartbeatCheckTimer) {
    clearInterval(heartbeatCheckTimer);
    heartbeatCheckTimer = null;
  }
}

function clearRestartRetryTimer(): void {
  if (restartRetryTimer) {
    clearTimeout(restartRetryTimer);
    restartRetryTimer = null;
  }
}

function scheduleRestartRetry(reason: string, overrideDelayMs?: number): void {
  if (restartRetryTimer) return;

  const delayMs =
    overrideDelayMs ?? Math.max(config.wsRestartCooldownMs, config.wsHeartbeatCheckIntervalMs);

  restartRetryTimer = setTimeout(() => {
    restartRetryTimer = null;
    void restartEventListener(`retry_after_failure:${reason}`);
  }, delayMs);

  if (typeof restartRetryTimer.unref === "function") {
    restartRetryTimer.unref();
  }

  log.warn({ reason, delayMs }, "Scheduled WebSocket listener retry");
}

async function restartEventListener(reason: string): Promise<void> {
  if (listenerRestarting) return;
  const now = Date.now();
  const sinceLastRestart = now - lastRestartAtMs;
  if (sinceLastRestart < config.wsRestartCooldownMs) {
    const remainingCooldownMs = config.wsRestartCooldownMs - sinceLastRestart;
    log.warn(
      {
        reason,
        cooldownMs: config.wsRestartCooldownMs,
        sinceLastRestartMs: sinceLastRestart,
        remainingCooldownMs,
      },
      "Skipping WebSocket listener restart due to cooldown"
    );
    scheduleRestartRetry(reason, remainingCooldownMs + 50);
    return;
  }

  clearRestartRetryTimer();
  listenerRestarting = true;
  restartCount += 1;
  lastRestartReason = reason;
  lastRestartError = null;
  lastRestartAtMs = now;
  const heartbeatAgeMs = lastHeartbeatAtMs > 0 ? now - lastHeartbeatAtMs : -1;

  try {
    log.warn(
      {
        reason,
        heartbeatAgeMs,
        lastHeartbeatBlock,
      },
      "Restarting WebSocket event listener"
    );

    await stopEventListener();

    // Catch up state while the WS stream was stale.
    try {
      await backfillEvents();
    } catch (err) {
      log.warn({ error: (err as Error).message }, "Backfill during WS restart failed");
    }

    await startEventListener();
    clearRestartRetryTimer();
    log.info("WebSocket event listener restarted");
  } catch (err) {
    lastRestartError = (err as Error).message;
    log.error({ error: (err as Error).message }, "WebSocket event listener restart failed");
    scheduleRestartRetry(reason);
  } finally {
    listenerRestarting = false;
  }
}

/**
 * Start listening for contract events via WebSocket.
 *
 * In ethers v6, contract.on() passes decoded args + a ContractEventPayload as the last arg.
 * The ContractEventPayload has `.log.blockNumber` for block tracking.
 */
export async function startEventListener(): Promise<void> {
  if (listenerRunning) {
    log.info("Contract event listeners already running");
    return;
  }

  try {
    const contract = getWsContract();
    wsProviderForListener = getWsProvider();
    listenerRunning = true;
    touchWsHeartbeat();

    wsBlockHandler = (blockNumber: number) => {
      touchWsHeartbeat(blockNumber);
    };
    wsProviderForListener.on("block", wsBlockHandler);

    // GameCreated(gameId, title, entryFee, minPlayers, maxPlayers, centerLat, centerLng)
    contract.on("GameCreated", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId, title] = args as [bigint, string, bigint, bigint, bigint, bigint, bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      log.info({ gameId: Number(gameId), title }, "GameCreated event");
      const cfg = await fetchGameConfig(Number(gameId));
      const shrinks = await fetchZoneShrinks(Number(gameId));
      manager.onGameCreated(cfg, shrinks);
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling GameCreated");
    }
    });

    // PlayerRegistered(gameId, playerNumber)
    contract.on("PlayerRegistered", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId, playerNum] = args as [bigint, bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      const pNum = Number(playerNum);
      log.info({ gameId: Number(gameId), playerNumber: pNum }, "PlayerRegistered event");
      const playerAddress = await fetchPlayerAddressWithRetry(Number(gameId), pNum);
      await manager.onPlayerRegistered(Number(gameId), playerAddress, pNum);
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling PlayerRegistered");
    }
    });

    // GameStarted(gameId, playerCount)
    contract.on("GameStarted", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId, playerCount] = args as [bigint, bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      log.info(
        { gameId: Number(gameId), playerCount: Number(playerCount) },
        "GameStarted event"
      );
      manager.onGameStarted(Number(gameId));
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling GameStarted");
    }
    });

    // GameEnded(gameId, winner1, winner2, winner3, topKiller) — all uint16 playerNumbers
    contract.on("GameEnded", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId, winner1, winner2, winner3, topKiller] = args as [bigint, bigint, bigint, bigint, bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      log.info({ gameId: Number(gameId), winner1: Number(winner1) }, "GameEnded event");
      manager.onGameEnded(Number(gameId), Number(winner1), Number(winner2), Number(winner3), Number(topKiller));
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling GameEnded");
    }
    });

    // GameCancelled(gameId)
    contract.on("GameCancelled", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId] = args as [bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      log.info({ gameId: Number(gameId) }, "GameCancelled event");
      manager.onGameCancelled(Number(gameId));
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling GameCancelled");
    }
    });

    // KillRecorded (just for logging — our server already knows about it)
    contract.on("KillRecorded", async (...args: unknown[]) => {
      touchWsHeartbeat();
      const [gameId, hunter, target] = args as [bigint, bigint, bigint];
      log.info(
        { gameId: Number(gameId), hunter: Number(hunter), target: Number(target) },
        "KillRecorded event (chain confirmation)"
      );
    });

    // PlayerEliminated (just for logging)
    contract.on("PlayerEliminated", async (...args: unknown[]) => {
      touchWsHeartbeat();
      const [gameId, playerNum, eliminator] = args as [bigint, bigint, bigint];
      log.info(
        { gameId: Number(gameId), playerNumber: Number(playerNum), eliminator: Number(eliminator) },
        "PlayerEliminated event (chain confirmation)"
      );
    });

    // PrizeClaimed(gameId, playerNumber, amount)
    contract.on("PrizeClaimed", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId, playerNum, amount] = args as [bigint, bigint, bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      const pNum = Number(playerNum);
      log.info(
        { gameId: Number(gameId), playerNumber: pNum, amount: amount.toString() },
        "PrizeClaimed event"
      );
      const playerAddress = await fetchPlayerAddressWithRetry(Number(gameId), pNum);
      manager.onPrizeClaimed(Number(gameId), playerAddress);
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling PrizeClaimed");
    }
    });

    // RefundClaimed(gameId, playerNumber, amount)
    contract.on("RefundClaimed", async (...args: unknown[]) => {
      const event = args[args.length - 1] as ContractEventPayload;
      touchWsHeartbeat(event.log.blockNumber);
      const [gameId, playerNum, amount] = args as [bigint, bigint, bigint];
      if (Number(gameId) < config.startGameId) { saveBlock(event.log.blockNumber); return; }
      try {
      const pNum = Number(playerNum);
      log.info(
        { gameId: Number(gameId), playerNumber: pNum, amount: amount.toString() },
        "RefundClaimed event"
      );
      const playerAddress = await fetchPlayerAddressWithRetry(Number(gameId), pNum);
      manager.onRefundClaimed(Number(gameId), playerAddress);
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling RefundClaimed");
    }
    });

    startHeartbeatMonitor();
    log.info(
      {
        checkIntervalMs: config.wsHeartbeatCheckIntervalMs,
        staleThresholdMs: config.wsHeartbeatStaleMs,
        restartCooldownMs: config.wsRestartCooldownMs,
      },
      "Contract event listeners started"
    );
  } catch (err) {
    listenerRunning = false;
    await stopEventListener();
    throw err;
  }
}

type ChainSyncMode = "backfill" | "rebuild";

interface ChainSyncResult {
  startId: number;
  nextId: number;
  currentBlock: number;
  syncedGames: number;
  failures: number;
}

function resolveLocalPlayerAddress(gameId: number, playerNumber: number): string {
  if (playerNumber === 0) return "";
  return getPlayerByNumber(gameId, playerNumber)?.address ?? "";
}

async function resolveSnapshotPlayerAddress(
  gameId: number,
  playerNumber: number,
  snapshots: Map<number, ResolvedPlayerState>
): Promise<string> {
  if (playerNumber === 0) return "";
  const fromSnapshot = snapshots.get(playerNumber);
  if (fromSnapshot) return fromSnapshot.address;
  try {
    return await fetchPlayerAddressWithRetry(gameId, playerNumber);
  } catch {
    return "";
  }
}

async function syncPlayersForGame(
  gameId: number,
  playerCount: number,
  mode: ChainSyncMode
): Promise<Map<number, ResolvedPlayerState>> {
  const snapshots = new Map<number, ResolvedPlayerState>();

  for (let pNum = 1; pNum <= playerCount; pNum++) {
    let onChainPlayer: ResolvedPlayerState;
    try {
      onChainPlayer = await fetchPlayerStateWithRetry(gameId, pNum);
    } catch (err) {
      if (mode === "rebuild") {
        log.error({ gameId, playerNumber: pNum, error: (err as Error).message }, "Failed to rebuild player");
        continue;
      }
      throw err;
    }

    snapshots.set(pNum, onChainPlayer);

    if (mode === "backfill") {
      const localPlayer = getPlayerByNumber(gameId, pNum);
      if (!localPlayer || localPlayer.address !== onChainPlayer.address) {
        await manager.onPlayerRegistered(gameId, onChainPlayer.address, pNum);
      }

      const refreshedPlayer = getPlayerByNumber(gameId, pNum);
      const chainAlive = onChainPlayer.killedAt === 0;
      const mergedAlive = refreshedPlayer ? refreshedPlayer.isAlive && chainAlive : chainAlive;
      const mergedKilledAt = mergedAlive
        ? null
        : (onChainPlayer.killedAt || (refreshedPlayer?.eliminatedAt ?? null));
      const mergedKills = Math.max(refreshedPlayer?.kills ?? 0, onChainPlayer.killCount);
      const mergedClaimed = (refreshedPlayer?.hasClaimed ?? false) || onChainPlayer.claimed;

      insertPlayer(gameId, onChainPlayer.address, pNum, {
        isAlive: mergedAlive,
        eliminatedAt: mergedKilledAt,
        kills: mergedKills,
        hasClaimed: mergedClaimed,
      });
      continue;
    }

    insertPlayer(gameId, onChainPlayer.address, pNum, {
      isAlive: onChainPlayer.killedAt === 0,
      eliminatedAt: onChainPlayer.killedAt === 0 ? null : onChainPlayer.killedAt,
      kills: onChainPlayer.killCount,
      hasClaimed: onChainPlayer.claimed,
    });
  }

  return snapshots;
}

async function applyBackfillPhase(
  gameId: number,
  gameState: Awaited<ReturnType<typeof fetchGameState>>
): Promise<void> {
  const localAfterPlayers = getGame(gameId);
  const localPhase = localAfterPlayers?.phase;

  if (gameState.phase === GamePhase.ACTIVE) {
    if (localPhase !== GamePhase.ACTIVE || localAfterPlayers?.subPhase == null) {
      manager.onGameStarted(gameId);
    }
    return;
  }

  if (gameState.phase === GamePhase.ENDED) {
    if (localPhase !== GamePhase.ENDED) {
      manager.onGameEnded(
        gameId,
        gameState.winner1,
        gameState.winner2,
        gameState.winner3,
        gameState.topKiller
      );
      return;
    }

    // Keep winner mapping and totals canonical even when already ended locally.
    updateGamePhase(gameId, GamePhase.ENDED, {
      winner1: resolveLocalPlayerAddress(gameId, gameState.winner1),
      winner2: resolveLocalPlayerAddress(gameId, gameState.winner2),
      winner3: resolveLocalPlayerAddress(gameId, gameState.winner3),
      topKiller: resolveLocalPlayerAddress(gameId, gameState.topKiller),
    });
    return;
  }

  if (gameState.phase === GamePhase.CANCELLED) {
    if (localPhase !== GamePhase.CANCELLED) {
      manager.onGameCancelled(gameId);
    } else {
      updateGamePhase(gameId, GamePhase.CANCELLED);
    }
    return;
  }

  if (localPhase !== GamePhase.REGISTRATION) {
    updateGamePhase(gameId, GamePhase.REGISTRATION);
  }
}

async function applyRebuildPhase(
  gameId: number,
  gameState: Awaited<ReturnType<typeof fetchGameState>>,
  snapshots: Map<number, ResolvedPlayerState>
): Promise<void> {
  if (gameState.phase === GamePhase.ENDED) {
    const winner1 = await resolveSnapshotPlayerAddress(gameId, gameState.winner1, snapshots);
    const winner2 = await resolveSnapshotPlayerAddress(gameId, gameState.winner2, snapshots);
    const winner3 = await resolveSnapshotPlayerAddress(gameId, gameState.winner3, snapshots);
    const topKiller = await resolveSnapshotPlayerAddress(gameId, gameState.topKiller, snapshots);

    updateGamePhase(gameId, GamePhase.ENDED, {
      winner1,
      winner2,
      winner3,
      topKiller,
    });
  } else if (gameState.phase === GamePhase.CANCELLED) {
    updateGamePhase(gameId, GamePhase.CANCELLED);
  }
}

async function syncSingleGameFromChain(gameId: number, mode: ChainSyncMode): Promise<void> {
  const [gameConfig, gameState, shrinks] = await Promise.all([
    fetchGameConfig(gameId),
    fetchGameState(gameId),
    fetchZoneShrinks(gameId),
  ]);

  if (mode === "backfill") {
    const localBefore = getGame(gameId);
    if (!localBefore) {
      manager.onGameCreated(gameConfig, shrinks);
    } else if (getDbZoneShrinks(gameId).length === 0) {
      insertZoneShrinks(gameId, shrinks);
    }
  } else {
    insertGame({
      ...gameConfig,
      phase: gameState.phase,
      totalCollected: gameState.totalCollected.toString(),
      playerCount: gameState.playerCount,
    });
    insertZoneShrinks(gameId, shrinks);
  }

  const playerSnapshots = await syncPlayersForGame(gameId, gameState.playerCount, mode);

  updatePlayerCount(gameId, gameState.playerCount);
  updateTotalCollected(gameId, gameState.totalCollected.toString());

  if (mode === "backfill") {
    await applyBackfillPhase(gameId, gameState);
    return;
  }

  await applyRebuildPhase(gameId, gameState, playerSnapshots);
  log.info({ gameId, phase: gameState.phase, players: gameState.playerCount }, "Rebuilt game");
}

async function syncGamesFromChain(mode: ChainSyncMode): Promise<ChainSyncResult> {
  const startId = config.startGameId;
  const nextId = await fetchNextGameId();
  const currentBlock = await getHttpProvider().getBlockNumber();

  if (mode === "rebuild") {
    // Always wipe existing game data first so REBUILD_DB provides a true reset
    // even when there are no chain games in the selected ID range.
    resetGameData();
  }

  if (startId >= nextId) {
    setSyncState(SYNC_KEY_LAST_BLOCK, currentBlock.toString());
    return {
      startId,
      nextId,
      currentBlock,
      syncedGames: 0,
      failures: 0,
    };
  }

  let syncedGames = 0;
  let failures = 0;

  for (let gameId = startId; gameId < nextId; gameId++) {
    try {
      await syncSingleGameFromChain(gameId, mode);
      syncedGames++;
    } catch (err) {
      failures++;
      const msg = mode === "backfill"
        ? "RPC snapshot backfill failed for game"
        : "Failed to rebuild game";
      log.error({ gameId, error: (err as Error).message }, msg);
    }

    // Small delay to avoid RPC rate limits during full rebuild.
    if (mode === "rebuild") {
      await sleep(500);
    }
  }

  setSyncState(SYNC_KEY_LAST_BLOCK, currentBlock.toString());
  return {
    startId,
    nextId,
    currentBlock,
    syncedGames,
    failures,
  };
}

/**
 * Backfill chain state from RPC snapshots (no historical log queries).
 */
export async function backfillEvents(): Promise<void> {
  const lastBlockStr = getSyncState(SYNC_KEY_LAST_BLOCK);
  const lastBlock = lastBlockStr ? parseInt(lastBlockStr, 10) : 0;

  const result = await syncGamesFromChain("backfill");
  if (result.startId >= result.nextId) {
    log.info({ currentBlock: result.currentBlock }, "Backfill complete (no chain games in configured range)");
    return;
  }

  log.info(
    {
      previousBlock: lastBlock,
      currentBlock: result.currentBlock,
      gameIdRange: `${result.startId}-${result.nextId - 1}`,
      syncedGames: result.syncedGames,
      failures: result.failures,
    },
    "Backfill complete"
  );
}

/**
 * Full rebuild: wipe the DB and re-sync all game data from the blockchain.
 * Uses config.startGameId to skip old/irrelevant games.
 * Queries chain state directly (no eth_getLogs / events) so it works
 * with any RPC provider regardless of block-range limits.
 * Player data is populated by iterating getPlayer(gameId, 1..playerCount).
 */
export async function rebuildFromChain(): Promise<void> {
  const result = await syncGamesFromChain("rebuild");
  if (result.startId >= result.nextId) {
    log.info({ startId: result.startId, nextId: result.nextId }, "No games to rebuild");
    return;
  }

  log.info(
    {
      startId: result.startId,
      nextId: result.nextId - 1,
      gamesRebuilt: result.syncedGames,
      failures: result.failures,
      currentBlock: result.currentBlock,
    },
    "DB rebuild complete"
  );
}

function saveBlock(blockNumber: number): void {
  const currentRaw = getSyncState(SYNC_KEY_LAST_BLOCK);
  const current = currentRaw ? parseInt(currentRaw, 10) : 0;
  if (!Number.isFinite(current) || blockNumber > current) {
    setSyncState(SYNC_KEY_LAST_BLOCK, blockNumber.toString());
  }
}

export function getEventListenerStatus(nowMs = Date.now()): EventListenerStatus {
  const wsReadyState = getWsReadyState();
  const heartbeatAgeMs = lastHeartbeatAtMs > 0 ? nowMs - lastHeartbeatAtMs : null;
  const lastBlockRaw = getSyncState(SYNC_KEY_LAST_BLOCK);
  const parsedLastBlock = lastBlockRaw ? parseInt(lastBlockRaw, 10) : NaN;
  const lastProcessedBlock = Number.isFinite(parsedLastBlock) ? parsedLastBlock : null;

  return {
    running: listenerRunning,
    restarting: listenerRestarting,
    wsReadyState: wsReadyState ?? null,
    heartbeatMonitorActive: heartbeatCheckTimer != null,
    lastHeartbeatAtMs: lastHeartbeatAtMs > 0 ? lastHeartbeatAtMs : null,
    lastHeartbeatBlock: lastHeartbeatBlock > 0 ? lastHeartbeatBlock : null,
    heartbeatAgeMs,
    lastRestartAtMs: lastRestartAtMs > 0 ? lastRestartAtMs : null,
    restartCount,
    lastRestartReason,
    lastRestartError,
    lastProcessedBlock,
  };
}

/**
 * Stop event listener and auto-start checker.
 */
export async function stopEventListener(): Promise<void> {
  stopHeartbeatMonitor();
  clearRestartRetryTimer();

  if (wsProviderForListener && wsBlockHandler) {
    wsProviderForListener.off("block", wsBlockHandler);
  }
  wsBlockHandler = null;
  wsProviderForListener = null;

  resetWsContract();
  await resetWsProvider();

  listenerRunning = false;
  lastHeartbeatAtMs = 0;
  lastHeartbeatBlock = 0;

  log.info("Event listener stopped");
}
