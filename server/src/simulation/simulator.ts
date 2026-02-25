import { ethers } from "ethers";
import { createLogger } from "../utils/logger.js";
import { contractCoordToDegrees, degreesToContractCoord, haversineDistance } from "../utils/geo.js";
import { encodeKillQrPayload } from "../utils/crypto.js";
import { config } from "../config.js";
import {
  getAlivePlayerCount,
  getAlivePlayers,
  getPlayer,
  getPlayerCount,
  getLatestLocationPing,
  getTargetAssignment,
  getGame,
  getPlayerByNumber,
  getPlayers,
  setPlayerCheckedIn,
  setPlayerBluetoothId,
  updateLastHeartbeat,
  getZoneShrinks,
} from "../db/queries.js";
import {
  handleLocationUpdate,
  handleKillSubmission,
  handleCheckin,
  addHybridSimulatedGame,
  removeSimulatedGame,
  broadcastToGame,
  getSimulatedGameIds,
  onPlayerRegistered,
} from "../game/manager.js";
import { getHttpProvider } from "../blockchain/client.js";
import { fetchPlayerInfo, getAbi } from "../blockchain/contract.js";
import { movePlayer, scatterPlayers, shouldAttemptKill } from "./playerAgent.js";
import type { SimulationConfig, SimulationStatus, SimulatedPlayer, DeploySimulationConfig } from "./types.js";
import { DEFAULT_ZONE_SHRINKS, ITEM_DEFINITIONS } from "./types.js";
import type { ItemId } from "./types.js";
import * as operator from "../blockchain/operator.js";
import { GamePhase } from "../utils/types.js";
import type { ZoneShrink } from "../utils/types.js";
import { normalizeBluetoothId } from "../game/ble.js";

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

const KILL_PROBABILITY_PER_TICK = 0.08;
const BASE_SPEED_MPS = 1.5; // meters per second walking speed
// Keep check-in phase visible for manual testing before bulk simulated check-ins complete.
const CHECKIN_SPREAD_DELAY_MS = 60_000;
const ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
const SIM_WALLET_ADDRESS_SET = new Set(
  SIM_WALLET_KEYS.map((key) => new ethers.Wallet(key).address.toLowerCase())
);

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
    minActiveDurationSeconds: cfg.minActiveDurationSeconds,
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

/**
 * Recover an in-progress simulation after a server restart.
 *
 * The game lifecycle (timers/subphases) is recovered by game manager.
 * This function restores the simulator loop (movement + simulated kills/items).
 */
export function recoverSimulation(): void {
  if (currentSimulation && currentSimulation.phase !== "ended" && currentSimulation.phase !== "aborted") {
    return;
  }

  const candidateIds = getSimulatedGameIds().sort((a, b) => b - a);
  const candidateGameId = candidateIds.find((id) => {
    const game = getGame(id);
    return Boolean(game && (game.phase === GamePhase.REGISTRATION || game.phase === GamePhase.ACTIVE));
  });

  if (!candidateGameId) {
    return;
  }

  const game = getGame(candidateGameId);
  if (!game) {
    return;
  }

  const centerLat = contractCoordToDegrees(game.centerLat);
  const centerLng = contractCoordToDegrees(game.centerLng);
  const shrinks = getZoneShrinks(candidateGameId);
  const initialRadius = shrinks[0]?.radiusMeters ?? 500;
  const dbPlayers = getPlayers(candidateGameId).filter((p) => p.address !== ZERO_ADDRESS);
  const baseCooldowns: Record<ItemId, number> = { ping_target: 0, ping_hunter: 0 };

  const simConfig: SimulationConfig = {
    playerCount: dbPlayers.length,
    centerLat,
    centerLng,
    initialRadiusMeters: initialRadius,
    speedMultiplier: 1,
    minActiveDurationSeconds: 60,
    title: game.title,
    entryFeeWei: game.entryFee.toString(),
  };

  const sim = new GameSimulator(simConfig);
  sim.gameId = candidateGameId;
  sim.setZoneShrinks(shrinks);
  sim.players = dbPlayers
    .filter((p) => SIM_WALLET_ADDRESS_SET.has(p.address))
    .map((p) => {
      const ping = getLatestLocationPing(candidateGameId, p.address);
      return {
        address: p.address.toLowerCase(),
        playerNumber: p.playerNumber,
      lat: ping?.lat ?? centerLat,
      lng: ping?.lng ?? centerLng,
      isAlive: p.isAlive,
      aggressiveness: 0.5 + Math.random() * 1.5,
        state: "wandering" as const,
        itemCooldowns: { ...baseCooldowns },
      };
    });

  currentSimulation = sim;

  void sim.resumeAfterRestart().catch((err) => {
    log.error({ gameId: candidateGameId, error: (err as Error).message }, "Failed to resume simulation after restart");
    sim.abort((err as Error).message);
  });
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
  private zoneShrinks: ZoneShrink[] = [];
  private simulatedAddressSet = new Set<string>(SIM_WALLET_ADDRESS_SET);

  constructor(cfg: SimulationConfig) {
    this.config = cfg;
    this.gameId = 0; // set after on-chain createGame
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

  /** Use numeric IDs so BLE normalization/verification matches real app format. */
  private simulatedBluetoothId(playerNumber: number): string {
    return String(9_000_000_000 + playerNumber);
  }

  private ensureBluetoothId(address: string, playerNumber: number, bluetoothId: string | null | undefined): string {
    const normalized = normalizeBluetoothId(bluetoothId);
    if (normalized) return normalized;

    const fallback = this.simulatedBluetoothId(playerNumber);
    setPlayerBluetoothId(this.gameId, address, fallback);
    return fallback;
  }

  /** Build a realistic nearby BLE list around the hunter position. */
  private buildNearbyBluetoothIds(
    hunterAddress: string,
    hunterLat: number,
    hunterLng: number,
    requiredBluetoothId?: string | null
  ): string[] {
    const ids = new Set<string>();
    const dbPlayers = getPlayers(this.gameId);
    const nearbyRadiusMeters = Math.max(config.killProximityMeters * 2, 150);

    for (const player of dbPlayers) {
      if (player.address === ZERO_ADDRESS) continue;
      if (player.address.toLowerCase() === hunterAddress.toLowerCase()) continue;

      let bluetoothId = normalizeBluetoothId(player.bluetoothId);
      if (!bluetoothId && this.simulatedAddressSet.has(player.address.toLowerCase())) {
        bluetoothId = this.ensureBluetoothId(player.address, player.playerNumber, player.bluetoothId);
      }
      if (!bluetoothId) continue;

      const ping = getLatestLocationPing(this.gameId, player.address);
      if (!ping) continue;

      const distance = haversineDistance(hunterLat, hunterLng, ping.lat, ping.lng);
      if (distance <= nearbyRadiusMeters) {
        ids.add(bluetoothId);
      }
    }

    const required = normalizeBluetoothId(requiredBluetoothId);
    if (required) {
      ids.add(required);
    }

    return Array.from(ids);
  }

  /**
   * Deploy a new game on-chain, register simulated players on-chain,
   * then wait for the server's normal lifecycle to handle everything:
   *   listener catches GameCreated → schedules deadline + gameDate timers
   *   listener catches PlayerRegistered → inserts players into DB
   *   deadline timer fires → checkDeadline() (cancel if < minPlayers)
   *   gameDate timer fires → checkGameDate() → operator.startGame()
   *   listener catches GameStarted → onGameStarted() (check-in, pregame, game)
   *   simulator auto-checks-in and simulates movement/kills once active
   */
  async deployAndSimulate(cfg: DeploySimulationConfig): Promise<void> {
    log.info(
      {
        playerCount: cfg.playerCount,
        speed: cfg.speedMultiplier,
        minActiveDurationSeconds: cfg.minActiveDurationSeconds,
        title: cfg.title,
        registrationDelaySeconds: cfg.registrationDelaySeconds,
        gameStartDelaySeconds: cfg.gameStartDelaySeconds,
        maxDurationSeconds: cfg.maxDurationSeconds,
      },
      "Deploying simulation game"
    );

    // --- Setup: use hardcoded wallets (saves testnet ETH across runs) ---
    this.phase = "setup";
    const wallets: ethers.Wallet[] = SIM_WALLET_KEYS
      .slice(0, cfg.playerCount)
      .map((key) => new ethers.Wallet(key));
    this.simulatedAddressSet = new Set(wallets.map((wallet) => wallet.address.toLowerCase()));
    log.info({ count: wallets.length }, "Loaded simulated wallets");

    // --- Create game on-chain ---
    this.phase = "registration";
    const wallNow = Math.floor(Date.now() / 1000);
    const latestBlock = await getHttpProvider().getBlock("latest");
    const chainNow = Number(latestBlock?.timestamp ?? wallNow);
    const now = Math.max(chainNow, wallNow);
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
      minPlayers: cfg.minPlayers,
      maxPlayers: Math.max(cfg.playerCount, 50), // allow real players to also join
      registrationDeadline,
      gameDate: registrationDeadline + cfg.gameStartDelaySeconds,
      maxDuration: cfg.maxDurationSeconds,
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

    const pendingWallets = [...wallets];
    const registeredWallets = new Set<string>();
    const registrationCutoffMs = registrationDeadline * 1000 - 2_000;
    let registrationRound = 0;

    while (pendingWallets.length > 0 && Date.now() < registrationCutoffMs) {
      registrationRound += 1;
      log.info(
        { gameId: this.gameId, round: registrationRound, pending: pendingWallets.length },
        "Simulation registration round"
      );

      for (let i = pendingWallets.length - 1; i >= 0; i--) {
        const wallet = pendingWallets[i];
        try {
          const needed = entryFee + gasBuffer;
          const balance = await provider.getBalance(wallet.address);
          if (balance < needed) {
            const topUp = needed - balance;
            log.info(
              { address: wallet.address.slice(0, 10), topUp: ethers.formatEther(topUp) },
              "Funding simulation wallet"
            );
            await operator.fundWallet(wallet.address, topUp);
          }

          // Register on-chain from the funded wallet
          const signer = wallet.connect(provider);
          const contract = new ethers.Contract(config.contractAddress, abi, signer);
          const regTx = await contract.register(this.gameId, { value: entryFee });
          await regTx.wait();

          registeredWallets.add(wallet.address.toLowerCase());
          pendingWallets.splice(i, 1);
          log.info(
            { gameId: this.gameId, address: wallet.address.slice(0, 10), remaining: pendingWallets.length },
            "Simulation wallet registered"
          );
        } catch (err) {
          log.warn(
            {
              gameId: this.gameId,
              address: wallet.address.slice(0, 10),
              error: (err as Error).message,
              pending: pendingWallets.length,
            },
            "Simulation wallet registration attempt failed; will retry while registration is open"
          );
        }
      }

      if (pendingWallets.length > 0 && Date.now() < registrationCutoffMs) {
        await sleep(1_500);
      }
    }

    if (pendingWallets.length > 0) {
      log.warn(
        { gameId: this.gameId, missed: pendingWallets.length, registered: registeredWallets.size, minPlayers: cfg.minPlayers },
        "Registration window ended before all simulation wallets registered"
      );
    }

    // Wait for blockchain listener to process PlayerRegistered events
    log.info("Waiting for PlayerRegistered events to be processed...");
    const expectedCount = registeredWallets.size;
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

    await this.reconcileRegisteredPlayersInDb(registeredWallets);

    // Build simulated player list from DB
    const initialCooldowns: Record<ItemId, number> = { ping_target: 0, ping_hunter: 0 };
    const dbPlayers = getPlayers(this.gameId);
    this.players = dbPlayers
      .filter((p) => this.simulatedAddressSet.has(p.address.toLowerCase()))
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
    this.phase = "registration";
    while (true) {
      const dbGame = getGame(this.gameId);
      if (!dbGame) { this.abort("Game deleted"); return; }
      if (dbGame.phase === GamePhase.CANCELLED) { this.abort("Game cancelled"); return; }
      if (dbGame.phase === GamePhase.ACTIVE && dbGame.subPhase === "checkin") {
        this.phase = "checkin";
        break;
      }
      if (dbGame.phase === GamePhase.ENDED) { this.phase = "ended"; this.cleanup(); return; }
      await sleep(1000);
    }

    // --- Auto-check-in simulated players during checkin phase ---
    log.info("Auto-checking in simulated players...");
    const scatterRadius = Math.min(cfg.initialRadiusMeters, 80);
    scatterPlayers(this.players, cfg.centerLat, cfg.centerLng, scatterRadius);
    for (const p of this.players) {
      handleLocationUpdate(this.gameId, p.address, p.lat, p.lng);
    }

    // Keep retrying until all simulated players are checked in (or checkin ends).
    const checkinWallDeadline = Date.now() + 180_000;
    let bootstrapSeeded = false;
    let bootstrapSeededAtMs: number | null = null;
    while (Date.now() < checkinWallDeadline) {
      const dbGame = getGame(this.gameId);
      if (!dbGame) { this.abort("Game deleted during checkin"); return; }
      if (dbGame.phase === GamePhase.CANCELLED) { this.abort("Game cancelled during checkin"); return; }
      if (dbGame.phase === GamePhase.ENDED) { this.phase = "ended"; this.cleanup(); return; }
      if (!(dbGame.phase === GamePhase.ACTIVE && dbGame.subPhase === "checkin")) {
        break;
      }

      const dbPlayersNow = getPlayers(this.gameId);
      const eligiblePlayers = dbPlayersNow.filter((p) => p.address !== ZERO_ADDRESS);
      const checkedInAddresses = new Set(
        eligiblePlayers.filter((p) => p.checkedIn).map((p) => p.address.toLowerCase())
      );
      const allHaveBluetooth = eligiblePlayers.every((player) => normalizeBluetoothId(player.bluetoothId) != null);
      if (checkedInAddresses.size >= eligiblePlayers.length && eligiblePlayers.length > 0 && allHaveBluetooth) {
        break;
      }

      let progress = false;

      // Bootstrap check-in chain when no seed exists yet (equivalent to server auto-seed).
      if (checkedInAddresses.size === 0 && !bootstrapSeeded && this.players.length > 0) {
        const seedPlayer = this.players[0];
        const seedBleId = this.simulatedBluetoothId(seedPlayer.playerNumber);
        setPlayerCheckedIn(this.gameId, seedPlayer.address, seedBleId);
        const updatedPlayers = getPlayers(this.gameId).filter((p) => p.address !== ZERO_ADDRESS);
        const checkedInCount = updatedPlayers.filter((p) => p.checkedIn).length;
        broadcastToGame(this.gameId, {
          type: "checkin:update",
          checkedInCount,
          totalPlayers: updatedPlayers.length,
          playerNumber: seedPlayer.playerNumber,
        });
        checkedInAddresses.add(seedPlayer.address);
        bootstrapSeeded = true;
        bootstrapSeededAtMs = Date.now();
        progress = true;
        log.info({ gameId: this.gameId, address: seedPlayer.address.slice(0, 10) }, "Bootstrapped simulation check-in seed");
      }

      const allowBulkCheckin =
        !bootstrapSeeded ||
        bootstrapSeededAtMs == null ||
        Date.now() - bootstrapSeededAtMs >= CHECKIN_SPREAD_DELAY_MS;
      if (!allowBulkCheckin) {
        await sleep(500);
        continue;
      }

      for (let i = 0; i < this.players.length; i++) {
        const p = this.players[i];
        const dbPlayer = eligiblePlayers.find((db) => db.address.toLowerCase() === p.address);
        if (!dbPlayer) continue;
        const hadBluetooth = normalizeBluetoothId(dbPlayer.bluetoothId) != null;
        const bleId = this.ensureBluetoothId(dbPlayer.address, dbPlayer.playerNumber, dbPlayer.bluetoothId);

        if (checkedInAddresses.has(p.address)) {
          // If this player was auto-seeded without bluetooth_id, finalize it.
          if (!hadBluetooth) {
            progress = true;
          }
          continue;
        }

        const checkedInPlayer = eligiblePlayers.find(
          (db) => db.checkedIn && db.address.toLowerCase() !== p.address
        );
        if (!checkedInPlayer) continue;

        const scannedBluetoothId = this.ensureBluetoothId(
          checkedInPlayer.address,
          checkedInPlayer.playerNumber,
          checkedInPlayer.bluetoothId
        );
        const qrPayload = encodeKillQrPayload(this.gameId, checkedInPlayer.playerNumber);
        const nearbyBle = this.buildNearbyBluetoothIds(p.address, p.lat, p.lng, scannedBluetoothId);
        const result = handleCheckin(this.gameId, p.address, p.lat, p.lng, qrPayload, bleId, nearbyBle);
        if (result.success) progress = true;
      }

      // Simulation mode fallback: ensure every registered player gets checked in.
      // This avoids real-device testers being eliminated in hybrid rounds where most
      // other participants are simulated and no practical scan partner exists.
      for (const player of eligiblePlayers) {
        const autoBle = this.ensureBluetoothId(player.address, player.playerNumber, player.bluetoothId);
        if (player.checkedIn) {
          continue;
        }
        setPlayerCheckedIn(this.gameId, player.address, autoBle);
        checkedInAddresses.add(player.address.toLowerCase());
        progress = true;
        broadcastToGame(this.gameId, {
          type: "checkin:update",
          checkedInCount: checkedInAddresses.size,
          totalPlayers: eligiblePlayers.length,
          playerNumber: player.playerNumber,
        });
      }

      if (!progress) {
        await sleep(1000);
      }
    }

    const finalPlayers = getPlayers(this.gameId);
    const finalCheckedInCount = finalPlayers.filter((p) => p.checkedIn).length;
    log.info({ checkedIn: finalCheckedInCount, totalPlayers: finalPlayers.length }, "Check-in status before pregame");

    // --- Wait for active gameplay (subPhase === "game") ---
    this.phase = "checkin";
    log.info("Waiting for active gameplay...");
    while (true) {
      const dbGame = getGame(this.gameId);
      if (!dbGame) { this.abort("Game deleted"); return; }
      if (dbGame.phase === GamePhase.CANCELLED) { this.abort("Game cancelled"); return; }
      if (dbGame.phase === GamePhase.ENDED) { this.phase = "ended"; this.cleanup(); return; }
      if (dbGame.subPhase === "pregame") {
        this.phase = "pregame";
      }
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

  async resumeAfterRestart(): Promise<void> {
    log.info({ gameId: this.gameId }, "Resuming simulation after restart");
    await this.reconcileRegisteredPlayersInDb(this.simulatedAddressSet);

    while (true) {
      const dbGame = getGame(this.gameId);
      if (!dbGame) {
        this.abort("Recovered simulation game not found");
        return;
      }

      this.refreshPlayersFromDb();

      if (dbGame.phase === GamePhase.CANCELLED || dbGame.phase === GamePhase.ENDED) {
        this.phase = "ended";
        this.cleanup();
        removeSimulatedGame(this.gameId);
        log.info({ gameId: this.gameId, phase: dbGame.phase }, "Recovered simulation already ended");
        return;
      }

      if (dbGame.phase === GamePhase.REGISTRATION) {
        this.phase = "registration";
        await sleep(1_000);
        continue;
      }

      if (dbGame.phase === GamePhase.ACTIVE && dbGame.subPhase === "checkin") {
        this.phase = "checkin";
        this.autoCheckinAllRegisteredPlayers();
        await sleep(1_000);
        continue;
      }

      if (dbGame.phase === GamePhase.ACTIVE && dbGame.subPhase === "pregame") {
        this.phase = "pregame";
        await sleep(500);
        continue;
      }

      if (dbGame.phase === GamePhase.ACTIVE && dbGame.subPhase === "game") {
        this.phase = "active";
        this.startedAtWall = dbGame.subPhaseStartedAt ?? Math.floor(Date.now() / 1000);

        if (!this.tickTimer) {
          const tickMs = Math.max(50, Math.round(1000 / this.config.speedMultiplier));
          this.tickTimer = setInterval(() => this.simulationTick(), tickMs);
          log.info({ gameId: this.gameId, tickMs }, "Recovered simulation active loop started");
        }
        return;
      }

      await sleep(1_000);
    }
  }

  private async reconcileRegisteredPlayersInDb(addresses: Set<string>): Promise<void> {
    for (const address of addresses) {
      try {
        const normalized = address.toLowerCase();
        if (normalized === ZERO_ADDRESS) continue;
        const info = await fetchPlayerInfo(this.gameId, normalized);
        if (!info.registered || info.playerNumber <= 0) continue;

        const dbPlayer = getPlayer(this.gameId, normalized);
        if (!dbPlayer || dbPlayer.playerNumber !== info.playerNumber) {
          await onPlayerRegistered(this.gameId, normalized, info.playerNumber);
        }
      } catch (err) {
        log.warn(
          { gameId: this.gameId, address: address.slice(0, 10), error: (err as Error).message },
          "Failed to reconcile simulation player in DB"
        );
      }
    }
  }

  private refreshPlayersFromDb(): void {
    const dbPlayers = getPlayers(this.gameId);
    const existing = new Map(this.players.map((p) => [p.address.toLowerCase(), p]));
    const baseCooldowns: Record<ItemId, number> = { ping_target: 0, ping_hunter: 0 };

    this.players = dbPlayers
      .filter((dbPlayer) => this.simulatedAddressSet.has(dbPlayer.address.toLowerCase()))
      .map((dbPlayer) => {
      const addr = dbPlayer.address.toLowerCase();
      const prev = existing.get(addr);
      const ping = getLatestLocationPing(this.gameId, addr);
      return {
        address: addr,
        playerNumber: dbPlayer.playerNumber,
        lat: ping?.lat ?? prev?.lat ?? this.config.centerLat,
        lng: ping?.lng ?? prev?.lng ?? this.config.centerLng,
        isAlive: dbPlayer.isAlive,
        aggressiveness: prev?.aggressiveness ?? (0.5 + Math.random() * 1.5),
        state: prev?.state ?? "wandering",
        itemCooldowns: prev?.itemCooldowns ?? { ...baseCooldowns },
      };
      });
  }

  private autoCheckinAllRegisteredPlayers(): void {
    const dbPlayers = getPlayers(this.gameId);
    let checkedInCount = dbPlayers.filter((p) => p.checkedIn).length;
    for (const player of dbPlayers) {
      if (player.address === ZERO_ADDRESS) continue;
      const autoBle = this.ensureBluetoothId(player.address, player.playerNumber, player.bluetoothId);
      if (player.checkedIn) continue;
      setPlayerCheckedIn(this.gameId, player.address, autoBle);
      checkedInCount += 1;
      broadcastToGame(this.gameId, {
        type: "checkin:update",
        checkedInCount,
        totalPlayers: dbPlayers.length,
        playerNumber: player.playerNumber,
      });
    }
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

    // Keep bot roster synced with DB in hybrid (real + simulated) games.
    this.refreshPlayersFromDb();
    const alive = this.players.filter((p) => p.isAlive);

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

    const elapsedActiveSeconds = this.startedAtWall > 0
      ? Math.floor(Date.now() / 1000) - this.startedAtWall
      : 0;
    if (elapsedActiveSeconds < this.config.minActiveDurationSeconds) {
      // Keep the round alive for spectator/app transition testing before resolving kills.
      this.tickCount++;
      if (this.tickCount % 100 === 0) {
        const hbNow = Math.floor(Date.now() / 1000);
        for (const p of this.players) {
          if (p.isAlive) {
            updateLastHeartbeat(this.gameId, p.address, hbNow);
          }
        }
      }
      this.simulateItems();
      return;
    }
    const forceResolve = elapsedActiveSeconds >= this.config.minActiveDurationSeconds;

    // Attempt kills
    for (const hunter of alive) {
      if (!hunter.isAlive) continue; // might have been killed earlier this tick

      const assignment = getTargetAssignment(this.gameId, hunter.address);
      if (!assignment) continue;

      const target = this.players.find((p) => p.address === assignment.targetAddress);
      if (!target || !target.isAlive) continue;

      if (forceResolve || shouldAttemptKill(
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

        // Force convergence for deterministic test completion in long-running rounds.
        if (forceResolve) {
          target.lat = hunter.lat;
          target.lng = hunter.lng;
          handleLocationUpdate(this.gameId, target.address, target.lat, target.lng);
        }

        const targetBluetoothId = this.ensureBluetoothId(
          targetPlayer.address,
          targetPlayer.playerNumber,
          targetPlayer.bluetoothId
        );
        const qrPayload = encodeKillQrPayload(this.gameId, target.playerNumber);
        const nearbyBle = this.buildNearbyBluetoothIds(
          hunter.address,
          hunter.lat,
          hunter.lng,
          targetBluetoothId
        );

        try {
          const result = await handleKillSubmission(
            this.gameId,
            hunter.address,
            qrPayload,
            hunter.lat,
            hunter.lng,
            nearbyBle
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
    const game = this.gameId > 0 ? getGame(this.gameId) : null;
    const playerCount = this.gameId > 0 ? getPlayerCount(this.gameId) : this.config.playerCount;
    const aliveCount = this.gameId > 0 ? getAlivePlayerCount(this.gameId) : this.players.filter((p) => p.isAlive).length;
    const elapsed = this.startedAtWall > 0
      ? Math.floor(Date.now() / 1000) - this.startedAtWall
      : 0;
    const derivedPhase: SimulationStatus["phase"] = game?.phase === GamePhase.ACTIVE
      ? game.subPhase === "game"
        ? "active"
        : game.subPhase === "pregame"
          ? "pregame"
          : "checkin"
      : game?.phase === GamePhase.REGISTRATION
        ? "registration"
        : game?.phase === GamePhase.CANCELLED || game?.phase === GamePhase.ENDED
          ? "ended"
          : this.phase;

    return {
      gameId: this.gameId,
      title: this.config.title,
      phase: derivedPhase,
      playerCount,
      aliveCount,
      killCount: this.killCount,
      elapsedSeconds: elapsed,
      speedMultiplier: this.config.speedMultiplier,
      minActiveDurationSeconds: this.config.minActiveDurationSeconds,
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
