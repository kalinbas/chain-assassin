import { ethers } from "ethers";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";
import { config } from "../config.js";
import { getHttpProvider, getWsProvider, getOperatorWallet } from "./client.js";
import { createLogger } from "../utils/logger.js";
import type { GameConfig, GameState, ZoneShrink, GamePhase } from "../utils/types.js";

const log = createLogger("contract");

const __dirname = dirname(fileURLToPath(import.meta.url));

// Load ABI from artifact
function loadAbi(): ethers.InterfaceAbi {
  const abiPath = resolve(__dirname, "../../abi/ChainAssassin.json");
  const artifact = JSON.parse(readFileSync(abiPath, "utf-8"));
  return artifact.abi;
}

let abi: ethers.InterfaceAbi;
let readContract: ethers.Contract;
let writeContract: ethers.Contract;
let wsContract: ethers.Contract;

function getAbi(): ethers.InterfaceAbi {
  if (!abi) abi = loadAbi();
  return abi;
}

/**
 * Read-only contract instance (HTTP provider).
 */
export function getReadContract(): ethers.Contract {
  if (!readContract) {
    readContract = new ethers.Contract(
      config.contractAddress,
      getAbi(),
      getHttpProvider()
    );
    log.info({ address: config.contractAddress }, "Read contract initialized");
  }
  return readContract;
}

/**
 * Write contract instance (operator wallet signer).
 */
export function getWriteContract(): ethers.Contract {
  if (!writeContract) {
    writeContract = new ethers.Contract(
      config.contractAddress,
      getAbi(),
      getOperatorWallet()
    );
    log.info("Write contract initialized (operator signer)");
  }
  return writeContract;
}

/**
 * Event-listening contract instance (WebSocket provider).
 */
export function getWsContract(): ethers.Contract {
  if (!wsContract) {
    wsContract = new ethers.Contract(
      config.contractAddress,
      getAbi(),
      getWsProvider()
    );
    log.info("WebSocket contract initialized for events");
  }
  return wsContract;
}

// ============ Read Helpers ============

export async function fetchGameConfig(gameId: number): Promise<GameConfig> {
  const c = getReadContract();
  const raw = await c.getGameConfig(gameId);
  return {
    gameId,
    title: raw.title,
    entryFee: raw.entryFee,
    minPlayers: Number(raw.minPlayers),
    maxPlayers: Number(raw.maxPlayers),
    registrationDeadline: Number(raw.registrationDeadline),
    expiryDeadline: Number(raw.expiryDeadline),
    createdAt: Number(raw.createdAt),
    creator: raw.creator,
    centerLat: Number(raw.centerLat),
    centerLng: Number(raw.centerLng),
    meetingLat: Number(raw.meetingLat),
    meetingLng: Number(raw.meetingLng),
    bps1st: Number(raw.bps1st),
    bps2nd: Number(raw.bps2nd),
    bps3rd: Number(raw.bps3rd),
    bpsKills: Number(raw.bpsKills),
    bpsPlatform: Number(raw.bpsPlatform),
  };
}

export async function fetchGameState(gameId: number): Promise<GameState> {
  const c = getReadContract();
  const raw = await c.getGameState(gameId);
  return {
    phase: Number(raw.phase) as GamePhase,
    playerCount: Number(raw.playerCount),
    totalCollected: raw.totalCollected,
    winner1: raw.winner1,
    winner2: raw.winner2,
    winner3: raw.winner3,
    topKiller: raw.topKiller,
  };
}

export async function fetchZoneShrinks(gameId: number): Promise<ZoneShrink[]> {
  const c = getReadContract();
  const raw = await c.getZoneShrinks(gameId);
  return raw.map((s: { atSecond: bigint; radiusMeters: bigint }) => ({
    atSecond: Number(s.atSecond),
    radiusMeters: Number(s.radiusMeters),
  }));
}

export async function fetchNextGameId(): Promise<number> {
  const c = getReadContract();
  return Number(await c.nextGameId());
}

export async function fetchIsRegistered(
  gameId: number,
  address: string
): Promise<boolean> {
  return getReadContract().isRegistered(gameId, address);
}

export async function fetchIsAlive(
  gameId: number,
  address: string
): Promise<boolean> {
  return getReadContract().isAlive(gameId, address);
}

export async function fetchPlayerInfo(
  gameId: number,
  address: string
): Promise<{ registered: boolean; alive: boolean; killCount: number; claimed: boolean; playerNumber: number }> {
  const c = getReadContract();
  const [registered, alive, killCount, claimed, playerNumber] = await c.getPlayerInfo(gameId, address);
  return {
    registered,
    alive,
    killCount: Number(killCount),
    claimed,
    playerNumber: Number(playerNumber),
  };
}
