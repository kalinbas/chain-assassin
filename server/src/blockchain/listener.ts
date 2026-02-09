import { ethers, ContractEventPayload } from "ethers";
import { getWsContract, getReadContract, fetchGameConfig, fetchZoneShrinks, fetchNextGameId } from "./contract.js";
import { getHttpProvider } from "./client.js";
import { getSyncState, setSyncState } from "../db/queries.js";
import * as manager from "../game/manager.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("listener");

const SYNC_KEY_LAST_BLOCK = "lastProcessedBlock";

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
    try {
      log.info({ gameId: Number(gameId), title }, "GameCreated event");
      const config = await fetchGameConfig(Number(gameId));
      const shrinks = await fetchZoneShrinks(Number(gameId));
      manager.onGameCreated(config, shrinks);
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling GameCreated");
    }
  });

  // PlayerRegistered(gameId, player, playerCount)
  contract.on("PlayerRegistered", async (...args: unknown[]) => {
    const event = args[args.length - 1] as ContractEventPayload;
    const [gameId, player, playerCount] = args as [bigint, string, bigint];
    try {
      log.info(
        { gameId: Number(gameId), player, playerCount: Number(playerCount) },
        "PlayerRegistered event"
      );
      manager.onPlayerRegistered(Number(gameId), player.toLowerCase(), Number(playerCount));
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling PlayerRegistered");
    }
  });

  // GameStarted(gameId, playerCount)
  contract.on("GameStarted", async (...args: unknown[]) => {
    const event = args[args.length - 1] as ContractEventPayload;
    const [gameId, playerCount] = args as [bigint, bigint];
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

  // GameEnded(gameId, winner1, winner2, winner3, topKiller)
  contract.on("GameEnded", async (...args: unknown[]) => {
    const event = args[args.length - 1] as ContractEventPayload;
    const [gameId, winner1, winner2, winner3, topKiller] = args as [bigint, string, string, string, string];
    try {
      log.info({ gameId: Number(gameId), winner1 }, "GameEnded event");
      manager.onGameEnded(Number(gameId), winner1, winner2, winner3, topKiller);
      saveBlock(event.log.blockNumber);
    } catch (err) {
      log.error({ error: (err as Error).message }, "Error handling GameEnded");
    }
  });

  // GameCancelled(gameId)
  contract.on("GameCancelled", async (...args: unknown[]) => {
    const event = args[args.length - 1] as ContractEventPayload;
    const [gameId] = args as [bigint];
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
    const [gameId, hunter, target] = args as [bigint, string, string];
    log.info(
      { gameId: Number(gameId), hunter, target },
      "KillRecorded event (chain confirmation)"
    );
  });

  // PlayerEliminated (just for logging)
  contract.on("PlayerEliminated", async (...args: unknown[]) => {
    const [gameId, player, eliminator] = args as [bigint, string, string];
    log.info(
      { gameId: Number(gameId), player, eliminator },
      "PlayerEliminated event (chain confirmation)"
    );
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
    log.info("No previous block state, skipping backfill");
    // Save current block as starting point
    const currentBlock = await getHttpProvider().getBlockNumber();
    setSyncState(SYNC_KEY_LAST_BLOCK, currentBlock.toString());
    return;
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

  // Backfill GameCreated events
  const createdEvents = await contract.queryFilter(
    contract.filters.GameCreated(),
    lastBlock + 1,
    currentBlock
  );
  for (const event of createdEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    log.info({ gameId }, "Backfilling GameCreated");
    try {
      const config = await fetchGameConfig(gameId);
      const shrinks = await fetchZoneShrinks(gameId);
      manager.onGameCreated(config, shrinks);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameCreated failed");
    }
  }

  // Backfill PlayerRegistered events
  const regEvents = await contract.queryFilter(
    contract.filters.PlayerRegistered(),
    lastBlock + 1,
    currentBlock
  );
  for (const event of regEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    const player = (e.args[1] as string).toLowerCase();
    const playerCount = Number(e.args[2]);
    log.info({ gameId, player }, "Backfilling PlayerRegistered");
    try {
      manager.onPlayerRegistered(gameId, player, playerCount);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill PlayerRegistered failed");
    }
  }

  // Backfill GameStarted events
  const startedEvents = await contract.queryFilter(
    contract.filters.GameStarted(),
    lastBlock + 1,
    currentBlock
  );
  for (const event of startedEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    log.info({ gameId }, "Backfilling GameStarted");
    try {
      manager.onGameStarted(gameId);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameStarted failed");
    }
  }

  // Backfill GameEnded events
  const endedEvents = await contract.queryFilter(
    contract.filters.GameEnded(),
    lastBlock + 1,
    currentBlock
  );
  for (const event of endedEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    log.info({ gameId }, "Backfilling GameEnded");
    try {
      manager.onGameEnded(
        gameId,
        e.args[1] as string,
        e.args[2] as string,
        e.args[3] as string,
        e.args[4] as string
      );
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameEnded failed");
    }
  }

  // Backfill GameCancelled events
  const cancelledEvents = await contract.queryFilter(
    contract.filters.GameCancelled(),
    lastBlock + 1,
    currentBlock
  );
  for (const event of cancelledEvents) {
    const e = event as ethers.EventLog;
    const gameId = Number(e.args[0]);
    log.info({ gameId }, "Backfilling GameCancelled");
    try {
      manager.onGameCancelled(gameId);
    } catch (err) {
      log.error({ gameId, error: (err as Error).message }, "Backfill GameCancelled failed");
    }
  }

  setSyncState(SYNC_KEY_LAST_BLOCK, currentBlock.toString());
  log.info({ newBlock: currentBlock }, "Backfill complete");
}

function saveBlock(blockNumber: number): void {
  setSyncState(SYNC_KEY_LAST_BLOCK, blockNumber.toString());
}

/**
 * Stop event listener and auto-start checker.
 */
export function stopEventListener(): void {
  const contract = getWsContract();
  contract.removeAllListeners();
  log.info("Event listener stopped");
}
