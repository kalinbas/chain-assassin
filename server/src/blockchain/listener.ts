import { ethers, ContractEventPayload } from "ethers";
import { getWsContract, getReadContract, fetchGameConfig, fetchGameState, fetchZoneShrinks, fetchNextGameId, fetchPlayer } from "./contract.js";
import { getHttpProvider } from "./client.js";
import { config } from "../config.js";
import {
  getSyncState,
  setSyncState,
  resetGameData,
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

const SYNC_KEY_LAST_BLOCK = "lastProcessedBlock";

// Base Sepolia block time ~2 seconds
const BLOCK_TIME_SECONDS = 2;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

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

/**
 * Estimate the block number for a given unix timestamp based on
 * the current block number and timestamp. Uses Base Sepolia's ~2s block time.
 */
async function estimateBlockForTimestamp(targetTimestamp: number): Promise<number> {
  const provider = getHttpProvider();
  const latest = await provider.getBlock("latest");
  if (!latest) throw new Error("Cannot fetch latest block");

  const secondsAgo = latest.timestamp - targetTimestamp;
  const blocksAgo = Math.floor(secondsAgo / BLOCK_TIME_SECONDS);
  // Add generous buffer (10%) to account for variable block times
  const estimated = latest.number - blocksAgo - Math.floor(blocksAgo * 0.1);
  return Math.max(0, estimated);
}

/**
 * Start listening for contract events via WebSocket.
 *
 * In ethers v6, contract.on() passes decoded args + a ContractEventPayload as the last arg.
 * The ContractEventPayload has `.log.blockNumber` for block tracking.
 */
export async function startEventListener(): Promise<void> {
  const contract = getWsContract();

  // GameCreated(gameId, title, entryFee, minPlayers, maxPlayers, centerLat, centerLng)
  contract.on("GameCreated", async (...args: unknown[]) => {
    const event = args[args.length - 1] as ContractEventPayload;
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
    const [gameId, hunter, target] = args as [bigint, bigint, bigint];
    log.info(
      { gameId: Number(gameId), hunter: Number(hunter), target: Number(target) },
      "KillRecorded event (chain confirmation)"
    );
  });

  // PlayerEliminated (just for logging)
  contract.on("PlayerEliminated", async (...args: unknown[]) => {
    const [gameId, playerNum, eliminator] = args as [bigint, bigint, bigint];
    log.info(
      { gameId: Number(gameId), playerNumber: Number(playerNum), eliminator: Number(eliminator) },
      "PlayerEliminated event (chain confirmation)"
    );
  });

  // PrizeClaimed(gameId, playerNumber, amount)
  contract.on("PrizeClaimed", async (...args: unknown[]) => {
    const event = args[args.length - 1] as ContractEventPayload;
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

  log.info("Contract event listeners started");
}

/**
 * Backfill missed events from the last processed block.
 */
export async function backfillEvents(): Promise<void> {
  const lastBlockStr = getSyncState(SYNC_KEY_LAST_BLOCK);
  const lastBlock = lastBlockStr ? parseInt(lastBlockStr, 10) : 0;

  if (lastBlock === 0) {
    // Find the block where startGameId was created and backfill from there
    const startBlock = await findGameCreatedBlock(config.startGameId);
    if (startBlock === null) {
      log.info({ startGameId: config.startGameId }, "No GameCreated event found for startGameId, saving current block");
      const currentBlock = await getHttpProvider().getBlockNumber();
      setSyncState(SYNC_KEY_LAST_BLOCK, currentBlock.toString());
      return;
    }
    log.info({ startGameId: config.startGameId, startBlock }, "Found creation block for startGameId, backfilling from there");
    // Set lastBlock to one before the creation block so backfill includes it
    setSyncState(SYNC_KEY_LAST_BLOCK, (startBlock - 1).toString());
    // Re-read and fall through to the normal backfill logic
    return backfillEvents();
  }

  const currentBlock = await getHttpProvider().getBlockNumber();
  if (lastBlock >= currentBlock) {
    log.info("No blocks to backfill");
    return;
  }

  log.info(
    { fromBlock: lastBlock + 1, toBlock: currentBlock },
    "Backfilling missed events"
  );

  const contract = getReadContract();
  const from = lastBlock + 1;

  // Backfill GameCreated events
  const createdEvents = await contract.queryFilter(contract.filters.GameCreated(), from, currentBlock);
  for (const event of createdEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    log.info({ gameId }, "Backfilling GameCreated");
    try {
      const cfg = await fetchGameConfig(gameId);
      const shrinks = await fetchZoneShrinks(gameId);
      manager.onGameCreated(cfg, shrinks);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameCreated failed");
    }
  }

  // Backfill PlayerRegistered events — now emits (gameId, playerNumber)
  const regEvents = await contract.queryFilter(contract.filters.PlayerRegistered(), from, currentBlock);
  for (const event of regEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    const pNum = Number(e.args[1]);
    log.info({ gameId, playerNumber: pNum }, "Backfilling PlayerRegistered");
    try {
      const playerAddress = await fetchPlayerAddressWithRetry(gameId, pNum);
      await manager.onPlayerRegistered(gameId, playerAddress, pNum);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill PlayerRegistered failed");
    }
  }

  // Backfill GameStarted events
  const startedEvents = await contract.queryFilter(contract.filters.GameStarted(), from, currentBlock);
  for (const event of startedEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    log.info({ gameId }, "Backfilling GameStarted");
    try {
      manager.onGameStarted(gameId);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameStarted failed");
    }
  }

  // Backfill GameEnded events — now emits (gameId, winner1, winner2, winner3, topKiller) as uint16
  const endedEvents = await contract.queryFilter(contract.filters.GameEnded(), from, currentBlock);
  for (const event of endedEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    log.info({ gameId }, "Backfilling GameEnded");
    try {
      manager.onGameEnded(
        gameId,
        Number(e.args[1]),
        Number(e.args[2]),
        Number(e.args[3]),
        Number(e.args[4])
      );
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameEnded failed");
    }
  }

  // Backfill GameCancelled events
  const cancelledEvents = await contract.queryFilter(contract.filters.GameCancelled(), from, currentBlock);
  for (const event of cancelledEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    log.info({ gameId }, "Backfilling GameCancelled");
    try {
      manager.onGameCancelled(gameId);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameCancelled failed");
    }
  }

  // Backfill PrizeClaimed events — now emits (gameId, playerNumber, amount)
  const prizeClaimedEvents = await contract.queryFilter(contract.filters.PrizeClaimed(), from, currentBlock);
  for (const event of prizeClaimedEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    const pNum = Number(e.args[1]);
    log.info({ gameId, playerNumber: pNum }, "Backfilling PrizeClaimed");
    try {
      const playerAddress = await fetchPlayerAddressWithRetry(gameId, pNum);
      manager.onPrizeClaimed(gameId, playerAddress);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill PrizeClaimed failed");
    }
  }

  // Backfill RefundClaimed events — now emits (gameId, playerNumber, amount)
  const refundClaimedEvents = await contract.queryFilter(contract.filters.RefundClaimed(), from, currentBlock);
  for (const event of refundClaimedEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    if (gameId < config.startGameId) continue;
    const pNum = Number(e.args[1]);
    log.info({ gameId, playerNumber: pNum }, "Backfilling RefundClaimed");
    try {
      const playerAddress = await fetchPlayerAddressWithRetry(gameId, pNum);
      manager.onRefundClaimed(gameId, playerAddress);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill RefundClaimed failed");
    }
  }

  setSyncState(SYNC_KEY_LAST_BLOCK, currentBlock.toString());
  log.info({ newBlock: currentBlock }, "Backfill complete");
}

/**
 * Full rebuild: wipe the DB and re-sync all game data from the blockchain.
 * Uses config.startGameId to skip old/irrelevant games.
 * Queries chain state directly (no eth_getLogs / events) so it works
 * with any RPC provider regardless of block-range limits.
 * Player data is populated by iterating getPlayer(gameId, 1..playerCount).
 */
export async function rebuildFromChain(): Promise<void> {
  const startId = config.startGameId;
  const nextId = await fetchNextGameId();

  // Always wipe existing game data first so REBUILD_DB provides a true reset
  // even when there are no chain games in the selected ID range.
  resetGameData();

  if (startId >= nextId) {
    log.info({ startId, nextId }, "No games to rebuild");
    const finalBlock = await getHttpProvider().getBlockNumber();
    setSyncState(SYNC_KEY_LAST_BLOCK, finalBlock.toString());
    return;
  }

  log.info({ startId, nextId: nextId - 1 }, "Rebuilding DB from chain...");

  for (let gameId = startId; gameId < nextId; gameId++) {
    try {
      // 1. Fetch game config + state + shrinks from chain
      const gameConfig = await fetchGameConfig(gameId);
      const gameState = await fetchGameState(gameId);
      const shrinks = await fetchZoneShrinks(gameId);

      // 2. Insert game with on-chain state
      insertGame({
        ...gameConfig,
        phase: gameState.phase,
        totalCollected: gameState.totalCollected.toString(),
        playerCount: gameState.playerCount,
      });
      insertZoneShrinks(gameId, shrinks);

      // 3. Iterate players 1..playerCount and insert into DB
      const rebuiltPlayers = new Map<number, ResolvedPlayerState>();
      for (let pNum = 1; pNum <= gameState.playerCount; pNum++) {
        try {
          const playerState = await fetchPlayerStateWithRetry(gameId, pNum);
          rebuiltPlayers.set(pNum, playerState);
          insertPlayer(gameId, playerState.address, pNum, {
            isAlive: playerState.killedAt === 0,
            eliminatedAt: playerState.killedAt === 0 ? null : playerState.killedAt,
            kills: playerState.killCount,
            hasClaimed: playerState.claimed,
          });
        } catch (err) {
          log.error({ gameId, playerNumber: pNum, error: (err as Error).message }, "Failed to rebuild player");
        }
      }

      // 4. Update winners/ended state if game is ended/cancelled
      if (gameState.phase === GamePhase.ENDED) {
        // Resolve winner playerNumbers to addresses for DB storage
        const resolveAddr = async (pNum: number): Promise<string> => {
          if (pNum === 0) return "";
          const fromSnapshot = rebuiltPlayers.get(pNum);
          if (fromSnapshot) return fromSnapshot.address;
          try {
            return await fetchPlayerAddressWithRetry(gameId, pNum);
          } catch {
            return "";
          }
        };

        const winner1Addr = await resolveAddr(gameState.winner1);
        const winner2Addr = await resolveAddr(gameState.winner2);
        const winner3Addr = await resolveAddr(gameState.winner3);
        const topKillerAddr = await resolveAddr(gameState.topKiller);

        updateGamePhase(gameId, GamePhase.ENDED, {
          winner1: winner1Addr,
          winner2: winner2Addr,
          winner3: winner3Addr,
          topKiller: topKillerAddr,
        });
      } else if (gameState.phase === GamePhase.CANCELLED) {
        updateGamePhase(gameId, GamePhase.CANCELLED);
      }

      // 5. Update player_count and totalCollected from chain (canonical)
      updatePlayerCount(gameId, gameState.playerCount);
      updateTotalCollected(gameId, gameState.totalCollected.toString());

      log.info({ gameId, phase: gameState.phase, players: gameState.playerCount }, "Rebuilt game");
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Failed to rebuild game");
    }

    // Small delay to avoid RPC rate limits
    await sleep(500);
  }

  // Save current block as sync point for future backfills
  const finalBlock = await getHttpProvider().getBlockNumber();
  setSyncState(SYNC_KEY_LAST_BLOCK, finalBlock.toString());

  log.info({ gamesRebuilt: nextId - startId, currentBlock: finalBlock }, "DB rebuild complete");
}

/**
 * Find the approximate block number where a specific game was created.
 * Uses timestamp estimation from game config (no eth_getLogs needed).
 */
async function findGameCreatedBlock(gameId: number): Promise<number | null> {
  try {
    const gameConfig = await fetchGameConfig(gameId);
    if (!gameConfig.createdAt) return null;
    return await estimateBlockForTimestamp(gameConfig.createdAt);
  } catch {
    return null;
  }
}

function saveBlock(blockNumber: number): void {
  const currentRaw = getSyncState(SYNC_KEY_LAST_BLOCK);
  const current = currentRaw ? parseInt(currentRaw, 10) : 0;
  if (!Number.isFinite(current) || blockNumber > current) {
    setSyncState(SYNC_KEY_LAST_BLOCK, blockNumber.toString());
  }
}

/**
 * Stop event listener and auto-start checker.
 */
export function stopEventListener(): void {
  const contract = getWsContract();
  contract.removeAllListeners();
  log.info("Event listener stopped");
}
