import { ethers } from "ethers";
import { createLogger } from "../utils/logger.js";
import { degreesToContractCoord } from "../utils/geo.js";
import { encodeKillQrPayload } from "../utils/crypto.js";
import { config } from "../config.js";
import {
  getAlivePlayerCount,
  getAlivePlayers,
  getLatestLocationPing,
  getTargetAssignment,
  getGame,
  getPlayerByNumber,
} from "../db/queries.js";
import {
  onGameCreated,
  onPlayerRegistered,
  onGameStarted,
  handleLocationUpdate,
  handleKillSubmission,
  handleCheckin,
  addSimulatedGame,
  removeSimulatedGame,
  broadcastToGame,
} from "../game/manager.js";
import { movePlayer, scatterPlayers, shouldAttemptKill } from "./playerAgent.js";
import type { SimulationConfig, SimulationStatus, SimulatedPlayer } from "./types.js";
import { DEFAULT_ZONE_SHRINKS, ITEM_DEFINITIONS } from "./types.js";
import type { ItemId } from "./types.js";
import { GamePhase } from "../utils/types.js";
import type { GameConfig, ZoneShrink } from "../utils/types.js";

const log = createLogger("simulator");

// Singleton — only one simulation at a time
let currentSimulation: GameSimulator | null = null;

const SIM_GAME_ID_START = 90001;
let nextSimGameId = SIM_GAME_ID_START;

const KILL_PROBABILITY_PER_TICK = 0.03;
const BASE_SPEED_MPS = 1.5; // meters per second walking speed

export function getSimulation(): GameSimulator | null {
  return currentSimulation;
}

export function startSimulation(cfg: SimulationConfig): GameSimulator {
  if (currentSimulation && currentSimulation.phase !== "ended" && currentSimulation.phase !== "aborted") {
    throw new Error("A simulation is already running. Stop it first.");
  }
  const sim = new GameSimulator(cfg);
  currentSimulation = sim;
  sim.start().catch((err) => {
    log.error({ error: err.message }, "Simulation failed");
    sim.abort(err.message);
  });
  return sim;
}

export function stopSimulation(): boolean {
  if (!currentSimulation) return false;
  currentSimulation.abort("Stopped by user");
  return true;
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

  async start(): Promise<void> {
    const { config: cfg } = this;
    log.info({ gameId: this.gameId, playerCount: cfg.playerCount, speed: cfg.speedMultiplier }, "Starting simulation");

    // --- Setup ---
    this.phase = "setup";
    const wallets: ethers.HDNodeWallet[] = [];
    for (let i = 0; i < cfg.playerCount; i++) {
      wallets.push(ethers.Wallet.createRandom());
    }

    // --- Create game ---
    this.phase = "registration";
    const now = Math.floor(Date.now() / 1000);

    if (cfg.useOnChain) {
      // TODO: implement on-chain flow
      throw new Error("On-chain simulation not yet implemented");
    }

    // Local-only: create game directly via manager
    const gameConfig: GameConfig = {
      gameId: this.gameId,
      title: cfg.title,
      entryFee: BigInt(cfg.entryFeeWei || "0"),
      minPlayers: cfg.playerCount,
      maxPlayers: cfg.playerCount,
      registrationDeadline: now + 3600,
      expiryDeadline: now + 7200,
      createdAt: now,
      creator: "0x0000000000000000000000000000000000000000",
      centerLat: degreesToContractCoord(cfg.centerLat),
      centerLng: degreesToContractCoord(cfg.centerLng),
      meetingLat: degreesToContractCoord(cfg.centerLat),
      meetingLng: degreesToContractCoord(cfg.centerLng),
      bps1st: 3500,
      bps2nd: 1500,
      bps3rd: 1000,
      bpsKills: 2000,
      bpsPlatform: 2000,
    };

    // Register this as a simulated game so the manager skips on-chain calls
    addSimulatedGame(this.gameId);

    onGameCreated(gameConfig, this.zoneShrinks);

    // --- Register players ---
    const initialCooldowns: Record<ItemId, number> = {
      ping_target: 0,
      ping_hunter: 0,
    };
    this.players = wallets.map((w, i) => ({
      address: w.address.toLowerCase(),
      playerNumber: i + 1,
      lat: cfg.centerLat,
      lng: cfg.centerLng,
      isAlive: true,
      aggressiveness: 0.5 + Math.random() * 1.5, // 0.5-2.0
      state: "wandering" as const,
      itemCooldowns: { ...initialCooldowns },
    }));

    for (let i = 0; i < wallets.length; i++) {
      onPlayerRegistered(this.gameId, wallets[i].address.toLowerCase(), i + 1);
    }

    // --- Check-in ---
    this.phase = "checkin";
    // Scatter players near the meeting point
    scatterPlayers(this.players, cfg.centerLat, cfg.centerLng, cfg.initialRadiusMeters);

    // Generate fake bluetooth IDs for simulated players
    const simBluetoothIds = this.players.map((_, i) => {
      const hex = () => Math.floor(Math.random() * 256).toString(16).padStart(2, "0").toUpperCase();
      return `SIM:${hex()}:${hex()}:${hex()}:${hex()}:${hex()}`;
    });

    const gpsOnlySlots = Math.max(1, Math.ceil(cfg.playerCount * 0.05));
    for (let i = 0; i < this.players.length; i++) {
      const p = this.players[i];
      const bleId = simBluetoothIds[i];
      if (i < gpsOnlySlots) {
        // GPS-only check-in
        handleCheckin(this.gameId, p.address, p.lat, p.lng, undefined, bleId);
      } else {
        // Need QR from a checked-in player
        const checkedInPlayer = this.players[Math.floor(Math.random() * gpsOnlySlots)];
        const qrPayload = encodeKillQrPayload(this.gameId, checkedInPlayer.playerNumber);
        handleCheckin(this.gameId, p.address, p.lat, p.lng, qrPayload, bleId);
      }
    }

    // Small delay to let WS clients connect before game starts
    await sleep(500);

    // --- Pregame phase ---
    this.phase = "pregame";
    onGameStarted(this.gameId); // Sets ACTIVE + sub_phase='pregame', schedules completePregame timer

    // Wait for manager's completePregame timer to fire
    while (this.phase === "pregame") {
      const dbGame = getGame(this.gameId);
      if (dbGame && dbGame.subPhase === "game") break;
      if (!dbGame || dbGame.phase === GamePhase.CANCELLED) {
        this.phase = "aborted";
        this.cleanup();
        return;
      }
      await sleep(500);
    }

    // --- Active play ---
    this.phase = "active";
    this.startedAtWall = Math.floor(Date.now() / 1000);

    // Send initial location pings for all players
    for (const p of this.players) {
      if (p.isAlive) {
        handleLocationUpdate(this.gameId, p.address, p.lat, p.lng);
      }
    }

    // --- Active play tick ---
    const tickMs = Math.max(50, Math.round(1000 / cfg.speedMultiplier));
    this.tickTimer = setInterval(() => this.simulationTick(), tickMs);

    log.info({ gameId: this.gameId }, "Simulation active — tick interval: " + tickMs + "ms");
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
