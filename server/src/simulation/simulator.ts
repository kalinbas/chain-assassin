import { ethers } from "ethers";
import { createLogger } from "../utils/logger.js";
import { degreesToContractCoord } from "../utils/geo.js";
import { encodeKillQrPayload } from "../utils/crypto.js";
import { config } from "../config.js";
import {
  getAlivePlayers,
  getLatestLocationPing,
  getTargetAssignment,
  getGame,
  getPlayerByNumber,
  getPlayers,
  updateLastHeartbeat,
} from "../db/queries.js";
import {
  handleLocationUpdate,
  handleKillSubmission,
  handleCheckin,
  addHybridSimulatedGame,
  removeSimulatedGame,
  broadcastToGame,
} from "../game/manager.js";
import { getHttpProvider } from "../blockchain/client.js";
import { getAbi } from "../blockchain/contract.js";
import { movePlayer, scatterPlayers, shouldAttemptKill } from "./playerAgent.js";
import type { SimulationConfig, SimulationStatus, SimulatedPlayer, DeploySimulationConfig } from "./types.js";
import { DEFAULT_ZONE_SHRINKS, ITEM_DEFINITIONS } from "./types.js";
import type { ItemId } from "./types.js";
import * as operator from "../blockchain/operator.js";
import { GamePhase } from "../utils/types.js";
import type { ZoneShrink } from "../utils/types.js";

const log = createLogger("simulator");

// Hardcoded sim wallets — reused across runs to save testnet ETH
const SIM_WALLET_KEYS = [
  "0xb76b6c4243dd5b149b218bcdeb4cc47a511764383af114dddb814b7aca41f52e", // 0xc33c...813c
  "0xf35320474eb0cb10c9d13c9aa58072087aa74c8592562aab646e032a6e0f2034", // 0x5E2b...85A9
  "0x048daaeb4ee9a4422c4a738cc782f06655b27df4d9b2382b65c954407e7793fb", // 0xDF40...2e03
  "0x4cf8c632b2d891589182bdf935fd0c06537452127c73a80e2e495d526b8156b6", // 0xc259...37e6
  "0x17fcb3f26bdcfe71933af2a33a17508fb8149adb3e801744685a70bcefdaa99f", // 0xbeF1...400e
];

// Singleton — only one simulation at a time
let currentSimulation: GameSimulator | null = null;

const SIM_GAME_ID_START = 90001;
let nextSimGameId = SIM_GAME_ID_START;

const KILL_PROBABILITY_PER_TICK = 0.03;
const BASE_SPEED_MPS = 1.5; // meters per second walking speed

export function getSimulation(): GameSimulator | null {
  return currentSimulation;
}

export function stopSimulation(): boolean {
  if (!currentSimulation) return false;
  currentSimulation.abort("Stopped by user");
  return true;
}

/**
 * Deploy a new game on-chain, register simulated players, then let the server's
 * normal lifecycle handle everything. One unified call for simulation.
 */
export function deploySimulation(cfg: DeploySimulationConfig): GameSimulator {
  if (currentSimulation && currentSimulation.phase !== "ended" && currentSimulation.phase !== "aborted") {
    throw new Error("A simulation is already running. Stop it first.");
  }

  const simConfig: SimulationConfig = {
    playerCount: cfg.playerCount,
    centerLat: cfg.centerLat,
    centerLng: cfg.centerLng,
    initialRadiusMeters: cfg.initialRadiusMeters,
    speedMultiplier: cfg.speedMultiplier,
    title: cfg.title,
    entryFeeWei: cfg.entryFeeWei,
  };

  const sim = new GameSimulator(simConfig);
  currentSimulation = sim;

  sim.deployAndSimulate(cfg).catch((err) => {
    log.error({ error: (err as Error).message }, "Deploy simulation failed");
    sim.abort((err as Error).message);
  });

  return sim;
}

export class GameSimulator {
  readonly config: SimulationConfig;
  gameId: number;
  phase: SimulationStatus["phase"] = "setup";
  players: SimulatedPlayer[] = [];
  killCount = 0;
  startedAtWall = 0; // wall-clock timestamp when active phase started
  private tickCount = 0;
  private tickTimer: ReturnType<typeof setInterval> | null = null;
  private abortReason: string | null = null;
  private zoneShrinks: ZoneShrink[] = [];

  constructor(cfg: SimulationConfig) {
    this.config = cfg;
    this.gameId = nextSimGameId++;
    // Build zone shrinks with speed multiplier
    const baseShrinks = DEFAULT_ZONE_SHRINKS.map((s) => ({
      ...s,
      radiusMeters: s.atSecond === 0 ? cfg.initialRadiusMeters : Math.round(cfg.initialRadiusMeters * (s.radiusMeters / 500)),
    }));
    // Compress times by speed multiplier
    this.zoneShrinks = baseShrinks.map((s) => ({
      atSecond: Math.round(s.atSecond / cfg.speedMultiplier),
      radiusMeters: s.radiusMeters,
    }));
  }

  /** Override zone shrinks (used by attach mode with DB-sourced shrinks). */
  setZoneShrinks(shrinks: ZoneShrink[]): void {
    this.zoneShrinks = shrinks;
  }

  /**
   * Deploy a new game on-chain, register simulated players on-chain,
   * then wait for the server's normal lifecycle to handle everything:
   *   listener catches GameCreated → schedules deadline timer
   *   listener catches PlayerRegistered → inserts players into DB
   *   deadline timer fires → checkAutoStart() → operator.startGame()
   *   listener catches GameStarted → onGameStarted() (check-in, pregame, game)
   *   simulator auto-checks-in and simulates movement/kills once active
   */
  async deployAndSimulate(cfg: DeploySimulationConfig): Promise<void> {
    log.info({ playerCount: cfg.playerCount, speed: cfg.speedMultiplier, title: cfg.title }, "Deploying simulation game");

    // --- Setup: use hardcoded wallets (saves testnet ETH across runs) ---
    this.phase = "setup";
    const wallets: ethers.Wallet[] = SIM_WALLET_KEYS
      .slice(0, cfg.playerCount)
      .map((key) => new ethers.Wallet(key));
    log.info({ count: wallets.length }, "Loaded simulated wallets");

    // --- Create game on-chain ---
    this.phase = "registration";
    const now = Math.floor(Date.now() / 1000);
    const registrationDeadline = now + cfg.registrationDelaySeconds;
    const entryFee = BigInt(cfg.entryFeeWei || "0");

    // Build zone shrinks for on-chain storage
    const onChainShrinks = this.zoneShrinks.map(s => ({
      atSecond: s.atSecond,
      radiusMeters: s.radiusMeters,
    }));

    const baseReward = BigInt(cfg.baseRewardWei || "0");
    const createGameParams: operator.CreateGameParams = {
      title: cfg.title,
      entryFee,
      minPlayers: cfg.playerCount,
      maxPlayers: Math.max(cfg.playerCount, 50), // allow real players to also join
      registrationDeadline,
      gameDate: registrationDeadline + 60, // 1 minute after registration closes
      maxDuration: 7200, // 2 hours max
      centerLat: degreesToContractCoord(cfg.centerLat),
      centerLng: degreesToContractCoord(cfg.centerLng),
      meetingLat: degreesToContractCoord(cfg.centerLat),
      meetingLng: degreesToContractCoord(cfg.centerLng),
      bps1st: 3500,
      bps2nd: 1500,
      bps3rd: 1000,
      bpsKills: 2000,
      bpsCreator: 1000, // sum with platformFeeBps (1000) must equal 10000
      baseRewardWei: baseReward > 0n ? baseReward : undefined,
    };

    log.info({ registrationDeadline, delaySeconds: cfg.registrationDelaySeconds }, "Creating game on-chain...");
    const createResult = await operator.createGame(createGameParams, onChainShrinks);
    this.gameId = createResult.gameId;
    log.info({ gameId: this.gameId, txHash: createResult.txHash }, "Game created on-chain");

    // Wait for blockchain listener to process GameCreated event
    log.info("Waiting for GameCreated event to be processed...");
    let gameInDb = false;
    for (let i = 0; i < 120; i++) {
      await sleep(500);
      const dbGame = getGame(this.gameId);
      if (dbGame) {
        gameInDb = true;
        break;
      }
    }
    if (!gameInDb) throw new Error("Game not found in DB after 60s — listener may be down");

    // Mark as hybrid simulated: skip recordKill/eliminatePlayer but keep endGame on-chain
    addHybridSimulatedGame(this.gameId);

    // --- Register simulated players on-chain ---
    const provider = getHttpProvider();
    const abi = getAbi();
    const gasBuffer = ethers.parseEther("0.001");

    for (let i = 0; i < wallets.length; i++) {
      const wallet = wallets[i];
      try {
        const needed = entryFee + gasBuffer;
        const balance = await provider.getBalance(wallet.address);
        if (balance < needed) {
          const topUp = needed - balance;
          log.info({ i: i + 1, total: wallets.length, address: wallet.address.slice(0, 10), topUp: ethers.formatEther(topUp) }, "Funding + registering wallet");
          await operator.fundWallet(wallet.address, topUp);
        } else {
          log.info({ i: i + 1, total: wallets.length, address: wallet.address.slice(0, 10) }, "Wallet already funded, registering");
        }

        // Register on-chain from the funded wallet
        const signer = wallet.connect(provider);
        const contract = new ethers.Contract(config.contractAddress, abi, signer);
        const regTx = await contract.register(this.gameId, { value: entryFee });
        await regTx.wait();

        log.info({ i: i + 1, address: wallet.address.slice(0, 10) }, "Registered successfully");
      } catch (err) {
        log.error({ i: i + 1, address: wallet.address.slice(0, 10), error: (err as Error).message }, "Failed to register wallet");
      }
    }

    // Wait for blockchain listener to process PlayerRegistered events
    log.info("Waiting for PlayerRegistered events to be processed...");
    const expectedCount = wallets.length;
    for (let attempt = 0; attempt < 120; attempt++) {
      await sleep(500);
      const dbPlayers = getPlayers(this.gameId);
      const simAddresses = new Set(wallets.map(w => w.address.toLowerCase()));
      const registeredSimCount = dbPlayers.filter(p => simAddresses.has(p.address.toLowerCase())).length;
      if (registeredSimCount >= expectedCount) {
        log.info({ registered: registeredSimCount }, "All simulated players registered in DB");
        break;
      }
      if (attempt % 20 === 0 && attempt > 0) {
        log.info({ registered: registeredSimCount, expected: expectedCount }, "Still waiting for registrations...");
      }
    }

    // Build simulated player list from DB
    const initialCooldowns: Record<ItemId, number> = { ping_target: 0, ping_hunter: 0 };
    const dbPlayers = getPlayers(this.gameId);
    const simAddressSet = new Set(wallets.map(w => w.address.toLowerCase()));
    this.players = dbPlayers
      .filter(p => simAddressSet.has(p.address.toLowerCase()))
      .map(p => ({
        address: p.address.toLowerCase(),
        playerNumber: p.playerNumber,
        lat: cfg.centerLat,
        lng: cfg.centerLng,
        isAlive: true,
        aggressiveness: 0.5 + Math.random() * 1.5,
        state: "wandering" as const,
        itemCooldowns: { ...initialCooldowns },
      }));

    log.info({ simPlayers: this.players.length, gameId: this.gameId }, "Simulated players ready, waiting for gameDate...");

    // --- Wait for startGame → checkin subPhase ---
    this.phase = "checkin";
    while (true) {
      const dbGame = getGame(this.gameId);
      if (!dbGame) { this.abort("Game deleted"); return; }
      if (dbGame.phase === GamePhase.CANCELLED) { this.abort("Game cancelled"); return; }
      if (dbGame.phase === GamePhase.ACTIVE && dbGame.subPhase === "checkin") break;
      if (dbGame.phase === GamePhase.ENDED) { this.phase = "ended"; this.cleanup(); return; }
      await sleep(1000);
    }

    // --- Auto-check-in simulated players during checkin phase ---
    log.info("Auto-checking in simulated players...");
    scatterPlayers(this.players, cfg.centerLat, cfg.centerLng, cfg.initialRadiusMeters);

    const simBluetoothIds = this.players.map(() => {
      const hex = () => Math.floor(Math.random() * 256).toString(16).padStart(2, "0").toUpperCase();
      return `SIM:${hex()}:${hex()}:${hex()}:${hex()}:${hex()}`;
    });

    const gpsOnlySlots = Math.max(1, Math.ceil(this.players.length * 0.05));
    for (let i = 0; i < this.players.length; i++) {
      const p = this.players[i];
      const bleId = simBluetoothIds[i];
      if (i < gpsOnlySlots) {
        handleCheckin(this.gameId, p.address, p.lat, p.lng, undefined, bleId);
      } else {
        const checkedInPlayer = this.players[Math.floor(Math.random() * gpsOnlySlots)];
        const qrPayload = encodeKillQrPayload(this.gameId, checkedInPlayer.playerNumber);
        handleCheckin(this.gameId, p.address, p.lat, p.lng, qrPayload, bleId);
      }
    }
    log.info({ count: this.players.length }, "Simulated players checked in");

    // --- Wait for active gameplay (subPhase === "game") ---
    this.phase = "pregame";
    log.info("Waiting for active gameplay...");
    while (true) {
      const dbGame = getGame(this.gameId);
      if (!dbGame) { this.abort("Game deleted"); return; }
      if (dbGame.phase === GamePhase.CANCELLED) { this.abort("Game cancelled"); return; }
      if (dbGame.phase === GamePhase.ENDED) { this.phase = "ended"; this.cleanup(); return; }
      if (dbGame.subPhase === "game") break;
      await sleep(500);
    }

    // --- Active play ---
    this.phase = "active";
    this.startedAtWall = Math.floor(Date.now() / 1000);

    // Send initial location pings
    for (const p of this.players) {
      if (p.isAlive) {
        handleLocationUpdate(this.gameId, p.address, p.lat, p.lng);
      }
    }

    // Start tick loop
    const tickMs = Math.max(50, Math.round(1000 / this.config.speedMultiplier));
    this.tickTimer = setInterval(() => this.simulationTick(), tickMs);
    log.info({ gameId: this.gameId, tickMs }, "Deploy simulation active");
  }

  private async simulationTick(): Promise<void> {
    if (this.phase !== "active") return;

    // Check if game is still active in the DB
    const game = getGame(this.gameId);
    if (!game || game.phase !== GamePhase.ACTIVE || game.subPhase !== "game") {
      // Game ended (the manager ended it)
      this.phase = "ended";
      this.cleanup();
      log.info({ gameId: this.gameId, killCount: this.killCount }, "Simulation ended (game concluded)");
      return;
    }

    const alive = this.players.filter((p) => p.isAlive);
    if (alive.length <= 1) {
      // Should have been caught by the manager, but just in case
      this.phase = "ended";
      this.cleanup();
      return;
    }

    // Get current zone radius from DB
    const dbGame = getGame(this.gameId);
    const currentZoneRadius = this.getCurrentZoneRadius();

    // Move each alive player
    for (const player of alive) {
      // Look up target position
      const assignment = getTargetAssignment(this.gameId, player.address);
      let targetLat: number | null = null;
      let targetLng: number | null = null;

      if (assignment) {
        const targetPing = getLatestLocationPing(this.gameId, assignment.targetAddress);
        if (targetPing) {
          targetLat = targetPing.lat;
          targetLng = targetPing.lng;
        }
      }

      // Move the player
      movePlayer(
        player,
        targetLat,
        targetLng,
        this.config.centerLat,
        this.config.centerLng,
        currentZoneRadius,
        BASE_SPEED_MPS
      );

      // Report location to the game manager
      handleLocationUpdate(this.gameId, player.address, player.lat, player.lng);
    }

    // Attempt kills
    for (const hunter of alive) {
      if (!hunter.isAlive) continue; // might have been killed earlier this tick

      const assignment = getTargetAssignment(this.gameId, hunter.address);
      if (!assignment) continue;

      const target = this.players.find((p) => p.address === assignment.targetAddress);
      if (!target || !target.isAlive) continue;

      if (shouldAttemptKill(
        hunter.lat,
        hunter.lng,
        target.lat,
        target.lng,
        config.killProximityMeters,
        KILL_PROBABILITY_PER_TICK,
        hunter.aggressiveness
      )) {
        // Construct valid kill submission
        const targetPlayer = getPlayerByNumber(this.gameId, target.playerNumber);
        if (!targetPlayer) continue;

        const qrPayload = encodeKillQrPayload(this.gameId, target.playerNumber);

        try {
          const result = await handleKillSubmission(
            this.gameId,
            hunter.address,
            qrPayload,
            hunter.lat,
            hunter.lng,
            [target.address] // BLE nearby addresses
          );

          if (result.success) {
            this.killCount++;
            target.isAlive = false;
            log.info(
              { gameId: this.gameId, hunter: hunter.address.slice(0, 8), target: target.address.slice(0, 8), kills: this.killCount },
              "Simulation kill"
            );
          }
        } catch (err) {
          log.error({ error: (err as Error).message }, "Kill submission failed in simulation");
        }
      }
    }

    // Sync alive status from DB (zone eliminations happen in the manager's gameTick)
    const dbAlivePlayers = getAlivePlayers(this.gameId);
    const dbAliveSet = new Set(dbAlivePlayers.map((p) => p.address));
    for (const p of this.players) {
      if (p.isAlive && !dbAliveSet.has(p.address)) {
        p.isAlive = false;
      }
    }

    // Item simulation — each alive player may use one item per tick
    this.tickCount++;

    // Refresh heartbeats for alive simulated players (~every 100 ticks)
    if (this.tickCount % 100 === 0) {
      const hbNow = Math.floor(Date.now() / 1000);
      for (const p of this.players) {
        if (p.isAlive) {
          updateLastHeartbeat(this.gameId, p.address, hbNow);
        }
      }
    }

    this.simulateItems();
  }

  private simulateItems(): void {
    const alive = this.players.filter((p) => p.isAlive);

    for (const player of alive) {
      // Decrement cooldowns
      for (const item of ITEM_DEFINITIONS) {
        if (player.itemCooldowns[item.id] > 0) {
          player.itemCooldowns[item.id]--;
        }
      }

      // Try to use one item (max 1 per tick)
      for (const item of ITEM_DEFINITIONS) {
        if (player.itemCooldowns[item.id] > 0) continue;

        const prob = item.probabilityPerTick * player.aggressiveness;
        if (Math.random() < prob) {
          // Use this item
          player.itemCooldowns[item.id] = item.cooldownTicks;

          // Find the relevant player's position for the ping circle
          let pingLat: number | null = null;
          let pingLng: number | null = null;

          if (item.id === "ping_target") {
            // Find this player's assigned target
            const assignment = getTargetAssignment(this.gameId, player.address);
            if (assignment) {
              const target = this.players.find((p) => p.address === assignment.targetAddress);
              if (target) {
                [pingLat, pingLng] = randomizeCircleCenter(target.lat, target.lng, item.radiusMeters);
              }
            }
          } else if (item.id === "ping_hunter") {
            // Find who is hunting this player
            const hunter = alive.find((p) => {
              const a = getTargetAssignment(this.gameId, p.address);
              return a && a.targetAddress === player.address;
            });
            if (hunter) {
              [pingLat, pingLng] = randomizeCircleCenter(hunter.lat, hunter.lng, item.radiusMeters);
            }
          }

          // Broadcast item:used event with ping circle data
          broadcastToGame(this.gameId, {
            type: "item:used",
            playerAddress: player.address,
            playerNumber: player.playerNumber,
            itemId: item.id,
            itemName: item.name,
            pingLat,
            pingLng,
            pingRadiusMeters: item.radiusMeters,
            pingDurationMs: item.durationTicks * 1000,
          });

          break; // max 1 item per tick
        }
      }
    }
  }

  private getCurrentZoneRadius(): number {
    const elapsed = Math.floor(Date.now() / 1000) - this.startedAtWall;
    let radius = this.zoneShrinks[0]?.radiusMeters ?? this.config.initialRadiusMeters;
    for (const shrink of this.zoneShrinks) {
      if (elapsed >= shrink.atSecond) {
        radius = shrink.radiusMeters;
      }
    }
    return radius;
  }

  abort(reason: string): void {
    this.abortReason = reason;
    this.phase = "aborted";
    this.cleanup();
    removeSimulatedGame(this.gameId);
    log.info({ gameId: this.gameId, reason }, "Simulation aborted");
  }

  private cleanup(): void {
    if (this.tickTimer) {
      clearInterval(this.tickTimer);
      this.tickTimer = null;
    }
  }

  getStatus(): SimulationStatus {
    const aliveCount = this.players.filter((p) => p.isAlive).length;
    const elapsed = this.startedAtWall > 0
      ? Math.floor(Date.now() / 1000) - this.startedAtWall
      : 0;

    return {
      gameId: this.gameId,
      title: this.config.title,
      phase: this.phase,
      playerCount: this.config.playerCount,
      aliveCount,
      killCount: this.killCount,
      elapsedSeconds: elapsed,
      speedMultiplier: this.config.speedMultiplier,
    };
  }
}

/** Offset a circle center randomly so the real position is somewhere inside the circle, not at the center */
function randomizeCircleCenter(realLat: number, realLng: number, radiusMeters: number): [number, number] {
  const angle = Math.random() * 2 * Math.PI;
  const dist = Math.random() * radiusMeters; // 0 to radiusMeters offset
  const dlat = (dist * Math.cos(angle)) / 111320;
  const dlng = (dist * Math.sin(angle)) / (111320 * Math.cos(realLat * Math.PI / 180));
  return [realLat + dlat, realLng + dlng];
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
