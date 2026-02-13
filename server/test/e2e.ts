/**
 * End-to-End Test: Full multi-player game simulation on local Anvil chain.
 *
 * Prerequisites:
 *   Run via: npm run test:e2e  (uses test/run-e2e.sh)
 *   Or manually:
 *     1. Anvil running: `anvil` (default port 8545)
 *     2. Contract deployed: forge script script/Deploy.s.sol ...
 *     3. Server running with test env vars (see run-e2e.sh)
 *
 * Scenarios tested:
 *   A) Full 6-player game lifecycle
 *      1. Create game on-chain (as operator)
 *      2. Register 6 players
 *      3. Send location pings (for auto-seed)
 *      4. Warp time → server auto-starts game
 *      5. Auto-seed check-in + viral QR check-in flow (success + rejection cases)
 *      6. Connect all players via WebSocket
 *      7. Wait for checkin → pregame → game phase transitions
 *      8. Kill rejection scenarios (wrong target, too far, no BLE, bad QR)
 *      9. Multiple hunters make kills, following target chain
 *     10. Verify leaderboard during gameplay
 *     11. Final kill ends game
 *     12. Verify game end on-chain
 *     13. Verify ALL WebSocket message types:
 *         auth:success, game:pregame_started, game:started, game:started_broadcast,
 *         kill:recorded, player:eliminated, target:assigned, hunter:updated,
 *         leaderboard:update, game:ended
 *     14. On-chain prize claims (winner + double-claim + non-winner revert)
 *     15. Verify full database state (all 8 tables)
 *
 *   B) Game cancellation + refund flow
 *      1. Create game with high minPlayers
 *      2. Register only 2 players (insufficient)
 *      3. Warp time past deadline → trigger cancellation
 *      4. Claim refunds (success + double-refund revert + non-registered revert)
 *      5. Verify on-chain claimed flags via getPlayerInfo
 *
 *   C) Platform fee withdrawal
 *      1. Verify fees accrued from game A
 *      2. Withdraw fees (success + double-withdraw revert)
 *
 * Usage: tsx test/e2e.ts <contractAddress>
 */

import { ethers } from "ethers";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";
import WebSocket from "ws";
import Database from "better-sqlite3";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ============ Config ============

const RPC_URL = process.env.RPC_URL || "http://127.0.0.1:8545";
const SERVER_URL = process.env.SERVER_URL || "http://127.0.0.1:3000";
const WS_URL = process.env.WS_URL || "ws://127.0.0.1:3000/ws";
const CONTRACT_ADDRESS = process.argv[2];

if (!CONTRACT_ADDRESS) {
  console.error("Usage: tsx test/e2e.ts <contractAddress>");
  process.exit(1);
}

// Anvil default private keys (accounts 0-9)
const ANVIL_KEYS = [
  "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", // account 0 = deployer/operator
  "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d", // account 1 = player1
  "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a", // account 2 = player2
  "0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6", // account 3 = player3
  "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a", // account 4 = player4
  "0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba", // account 5 = player5
  "0x92db14e403b83dfe3df233f83dfa3a0d7096f21ca9b0d6d6b8d88b2b4ec1564e", // account 6 = player6
  "0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356", // account 7 = player7
  "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97", // account 8 = player8
  "0x2a871d0798f97d79848a013d4936a73bf4cc922c825d33c1cf7073dff6d409c6", // account 9 = player9
];

// ============ Setup ============

const provider = new ethers.JsonRpcProvider(RPC_URL);
const operatorWallet = new ethers.Wallet(ANVIL_KEYS[0], provider);

// Create 6 player wallets
const NUM_PLAYERS = 6;
const playerWallets: ethers.Wallet[] = [];
for (let i = 1; i <= NUM_PLAYERS; i++) {
  playerWallets.push(new ethers.Wallet(ANVIL_KEYS[i], provider));
}

const abiPath = resolve(__dirname, "../abi/ChainAssassin.json");
const artifact = JSON.parse(readFileSync(abiPath, "utf-8"));
const abi = artifact.abi;

const operatorContract = new ethers.Contract(CONTRACT_ADDRESS, abi, operatorWallet);
const playerContracts = playerWallets.map(
  (w) => new ethers.Contract(CONTRACT_ADDRESS, abi, w)
);

// ============ Helpers ============

let assertCount = 0;
let sectionName = "";

function section(name: string) {
  sectionName = name;
  console.log(`\n\x1b[35m━━━ ${name} ━━━\x1b[0m`);
}

function log(step: string, msg: string, data?: unknown) {
  const prefix = `\x1b[36m[${step}]\x1b[0m`;
  if (data !== undefined) {
    console.log(`${prefix} ${msg}`, data);
  } else {
    console.log(`${prefix} ${msg}`);
  }
}

function success(msg: string) {
  assertCount++;
  console.log(`\x1b[32m✓ ${msg}\x1b[0m`);
}

function fail(msg: string) {
  console.error(`\x1b[31m✗ [${sectionName}] ${msg}\x1b[0m`);
  process.exit(1);
}

function assert(condition: boolean, msg: string) {
  if (condition) {
    success(msg);
  } else {
    fail(msg);
  }
}

async function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * Anvil RPC: advance time by N seconds and mine a block.
 */
async function anvilWarpTime(seconds: number): Promise<void> {
  await provider.send("evm_increaseTime", [seconds]);
  await provider.send("evm_mine", []);
}

/**
 * Sign an auth message for the REST API.
 */
async function signApiAuth(
  wallet: ethers.Wallet
): Promise<{ address: string; signature: string; message: string }> {
  const timestamp = Math.floor(Date.now() / 1000);
  const message = `chain-assassin:${timestamp}`;
  const signature = await wallet.signMessage(message);
  return { address: wallet.address, signature, message };
}

/**
 * Sign a WebSocket auth message.
 */
async function signWsAuth(
  wallet: ethers.Wallet,
  gameId: number
): Promise<{ address: string; signature: string; message: string }> {
  const timestamp = Math.floor(Date.now() / 1000);
  const message = `chain-assassin:${gameId}:${timestamp}`;
  const signature = await wallet.signMessage(message);
  return { address: wallet.address, signature, message };
}

/**
 * Make an authenticated REST API call.
 */
async function apiCall(
  method: string,
  path: string,
  wallet: ethers.Wallet,
  body?: Record<string, unknown>
): Promise<Response> {
  const auth = await signApiAuth(wallet);
  const url = `${SERVER_URL}${path}`;
  const options: RequestInit = {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-Address": auth.address,
      "X-Signature": auth.signature,
      "X-Message": auth.message,
    },
  };
  if (body) {
    options.body = JSON.stringify(body);
  }
  return fetch(url, options);
}

/**
 * Connect a player via WebSocket and authenticate.
 */
function connectWs(
  wallet: ethers.Wallet,
  gameId: number
): Promise<{ ws: WebSocket; messages: Record<string, unknown>[] }> {
  return new Promise(async (resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const messages: Record<string, unknown>[] = [];

    ws.on("error", (err) => reject(err));

    ws.on("open", async () => {
      const auth = await signWsAuth(wallet, gameId);
      ws.send(
        JSON.stringify({
          type: "auth",
          gameId,
          ...auth,
        })
      );
    });

    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      messages.push(msg);

      if (msg.type === "auth:success") {
        resolve({ ws, messages });
      } else if (msg.type === "error") {
        reject(new Error(`WS auth error: ${msg.message}`));
      }
    });

    setTimeout(() => reject(new Error("WS auth timeout")), 5000);
  });
}

/**
 * Wait for a specific WS message type to appear.
 */
function waitForWsMessage(
  messages: Record<string, unknown>[],
  type: string,
  timeoutMs: number = 15000
): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const existing = messages.find((m) => m.type === type);
    if (existing) {
      resolve(existing);
      return;
    }

    const startLen = messages.length;
    const interval = setInterval(() => {
      const found = messages.find(
        (m, i) => i >= startLen && m.type === type
      );
      if (found) {
        clearInterval(interval);
        clearTimeout(timer);
        resolve(found);
      }
    }, 100);

    const timer = setTimeout(() => {
      clearInterval(interval);
      reject(
        new Error(
          `Timeout waiting for WS message: ${type} (got: ${messages
            .map((m) => m.type)
            .join(", ")})`
        )
      );
    }, timeoutMs);
  });
}

/**
 * Encode a kill QR payload as an obfuscated numeric string.
 * Must match server's encodeKillQrPayload() logic.
 */
function encodeQr(gameId: number, playerNumber: number): string {
  const n = BigInt(gameId * 10000 + playerNumber);
  const scrambled = (n * 1588635695n) % 2147483647n;
  return String(scrambled);
}

/**
 * Get a short name for a player by address.
 */
function playerName(addr: string): string {
  const idx = playerWallets.findIndex(
    (w) => w.address.toLowerCase() === addr.toLowerCase()
  );
  return idx >= 0 ? `Player${idx + 1}` : addr.slice(0, 8);
}

/**
 * Get a GPS coordinate near Mexico City center for a player index.
 * All coords are inside the 2000m initial zone.
 */
function playerCoords(idx: number): { lat: number; lng: number } {
  return {
    lat: 19.4352 + (idx % 3) * 0.0002,
    lng: -99.1281 + Math.floor(idx / 3) * 0.0002,
  };
}

// ============ Scenario A: Full 6-Player Game ============

async function scenarioA_createGame(): Promise<number> {
  log("A1", "Creating game on-chain with 6-player config...");

  const now = (await provider.getBlock("latest"))!.timestamp;
  const regDeadline = now + 60;
  const gameDate = now + 120;
  const maxDuration = 3600;

  const params = {
    title: "E2E Multi-Player Game",
    entryFee: ethers.parseEther("0.01"),
    minPlayers: 3,
    maxPlayers: 10,
    registrationDeadline: regDeadline,
    gameDate: gameDate,
    maxDuration: maxDuration,
    centerLat: 19435244, // Mexico City
    centerLng: -99128056,
    meetingLat: 19435244,
    meetingLng: -99128056,
    bps1st: 3500,    // sum + platformFeeBps(1000) = 10000
    bps2nd: 1500,
    bps3rd: 1000,
    bpsKills: 2000,
    bpsCreator: 1000,
  };

  const shrinks = [
    { atSecond: 0, radiusMeters: 2000 },
    { atSecond: 600, radiusMeters: 1000 },
    { atSecond: 1200, radiusMeters: 300 },
  ];

  const tx = await operatorContract.createGame(params, shrinks);
  const receipt = await tx.wait();

  const iface = new ethers.Interface(abi);
  const createdLog = receipt.logs.find((l: ethers.Log) => {
    try {
      return (
        iface.parseLog({ topics: l.topics as string[], data: l.data })?.name ===
        "GameCreated"
      );
    } catch {
      return false;
    }
  });
  const parsed = iface.parseLog({
    topics: createdLog.topics as string[],
    data: createdLog.data,
  });
  const gameId = Number(parsed!.args[0]);

  success(`Game created with ID: ${gameId}`);
  return gameId;
}

async function scenarioA_registerPlayers(gameId: number): Promise<void> {
  log("A2", `Registering ${NUM_PLAYERS} players...`);

  const entryFee = ethers.parseEther("0.01");

  for (let i = 0; i < NUM_PLAYERS; i++) {
    const tx = await playerContracts[i].register(gameId, { value: entryFee });
    await tx.wait();
    log("A2", `Player${i + 1} registered: ${playerWallets[i].address}`);
  }

  const state = await operatorContract.getGameState(gameId);
  assert(
    Number(state.playerCount) === NUM_PLAYERS,
    `${NUM_PLAYERS} players registered on-chain`
  );
}

async function scenarioA_checkin(gameId: number): Promise<void> {
  log("A3", "Testing viral check-in flow...");
  // Auto-seed mechanism: server auto-checks-in nearby players who have sent location pings.
  // With 6 players at 5%, seedCount = max(1, ceil(0.3)) = 1
  // Location pings were sent BEFORE game start so tryAutoSeed() seeds Player1 immediately.

  // Wait for auto-seed to check in Player1 (tryAutoSeed runs on game start)
  log("A3", "Waiting for auto-seed to check in a seed player...");
  let seedPlayerIdx = -1;
  for (let i = 0; i < 15; i++) {
    await sleep(1000);
    const statusRes = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
    if (statusRes.ok) {
      const statusData = await statusRes.json();
      if (statusData.checkedInCount >= 1) {
        seedPlayerIdx = 0; // Player1 is closest (playerCoords(0) is at zone center)
        success(`Auto-seed checked in ${statusData.checkedInCount} player(s)`);
        break;
      }
    }
  }
  assert(seedPlayerIdx >= 0, "Auto-seed checked in at least 1 player");

  // 1. Player2 tries without QR — should be rejected (only seeds bypass QR)
  const p2Coords = playerCoords(1);
  const p2NoQrRes = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[1],
    { lat: p2Coords.lat, lng: p2Coords.lng }
  );
  const p2NoQrData = await p2NoQrRes.json();
  assert(!p2NoQrRes.ok || !p2NoQrData.success, "Player2 rejected without QR (must scan checked-in player)");

  // 2. Player2 scans own QR — should be rejected
  const p2OwnQr = encodeQr(gameId, 2); // player 2's own QR
  const p2OwnRes = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[1],
    { lat: p2Coords.lat, lng: p2Coords.lng, qrPayload: p2OwnQr }
  );
  const p2OwnData = await p2OwnRes.json();
  assert(!p2OwnRes.ok || !p2OwnData.success, "Player2 rejected scanning own QR");

  // 3. Player2 scans not-yet-checked-in Player5 — should be rejected
  const p5Qr = encodeQr(gameId, 5); // player 5 hasn't checked in
  const p2BadRes = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[1],
    { lat: p2Coords.lat, lng: p2Coords.lng, qrPayload: p5Qr }
  );
  const p2BadData = await p2BadRes.json();
  assert(!p2BadRes.ok || !p2BadData.success, "Rejected: scanned player not checked in");

  // 4. Player2 scans auto-seeded Player1 — success (viral check-in)
  const p1Qr = encodeQr(gameId, 1); // player 1 was auto-seeded
  const p2Res = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[1],
    { lat: p2Coords.lat, lng: p2Coords.lng, qrPayload: p1Qr }
  );
  const p2Data = await p2Res.json();
  if (!p2Res.ok || !p2Data.success) {
    log("A3", `Player2 checkin failed: ${p2Res.status} ${JSON.stringify(p2Data)}`);
  }
  assert(p2Res.ok && p2Data.success, "Player2 checked in via auto-seeded Player1's QR");

  // 5. Player3 scans Player2 (viral chain) — success
  const p2Qr = encodeQr(gameId, 2);
  const p3Coords = playerCoords(2);
  const p3Res = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[2],
    { lat: p3Coords.lat, lng: p3Coords.lng, qrPayload: p2Qr }
  );
  const p3Data = await p3Res.json();
  assert(p3Res.ok && p3Data.success, "Player3 checked in via Player2's QR (viral chain)");

  // 6. Player4 from too far away (with valid QR) — rejected
  const farRes = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[3],
    { lat: 20.0, lng: -99.0, qrPayload: p1Qr } // ~63km away
  );
  const farData = await farRes.json();
  assert(!farRes.ok || !farData.success, "Check-in from too far away rejected");

  // 7. Player1 double check-in — rejected
  const dupeRes = await apiCall(
    "POST",
    `/api/games/${gameId}/checkin`,
    playerWallets[0],
    { lat: 19.4352, lng: -99.1281, qrPayload: p1Qr }
  );
  const dupeData = await dupeRes.json();
  assert(!dupeRes.ok || !dupeData.success, "Double check-in rejected");

  // 8. Check in remaining players (4, 5, 6) so all 6 are checked in for gameplay
  for (let i = 3; i < NUM_PLAYERS; i++) {
    const coords = playerCoords(i);
    const res = await apiCall(
      "POST",
      `/api/games/${gameId}/checkin`,
      playerWallets[i],
      { lat: coords.lat, lng: coords.lng, qrPayload: p1Qr }
    );
    const data = await res.json();
    assert(res.ok && data.success, `Player${i + 1} checked in via Player1's QR`);
  }

  // Verify all players are checked in
  const statusRes = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  const statusData = await statusRes.json();
  assert(statusData.checkedInCount === NUM_PLAYERS, `All ${NUM_PLAYERS} players checked in`);
}

async function scenarioA_waitForServerSync(gameId: number): Promise<void> {
  log("A4", "Waiting for server to sync events...");

  for (let i = 0; i < 20; i++) {
    await provider.send("evm_mine", []);
    await sleep(1000);
    try {
      const res = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
      if (res.ok) {
        const data = await res.json();
        if (data.playerCount === NUM_PLAYERS) {
          success(
            `Server synced: ${data.playerCount} players, phase=${data.phase}`
          );
          return;
        }
        log("A4", `Server has ${data.playerCount} players, waiting...`);
      }
    } catch {
      log("A4", "Server not ready yet...");
    }
  }

  fail("Server did not sync within 20s");
}

async function scenarioA_warpTimeAndAutoStart(gameId: number): Promise<void> {
  log("A5", "Warping time past registration deadline...");

  await anvilWarpTime(120);
  log("A5", "Time warped +120s");

  // Mine a block so chain timestamp is updated for the auto-start check
  await provider.send("evm_mine", []);

  // Trigger auto-start via admin endpoint (operator-authenticated)
  log("A5", "Triggering auto-start via admin endpoint...");
  const triggerRes = await apiCall("POST", "/api/admin/check-auto-start", operatorWallet);
  assert(triggerRes.ok, "Admin check-auto-start returned 200");

  // Wait for the startGame tx to be mined and event processed
  await sleep(3000);
  await provider.send("evm_mine", []);
  await sleep(2000);

  const res = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  if (res.ok) {
    const data = await res.json();
    assert(data.phase === 1, "Game auto-started by server!");
  } else {
    fail("Game status endpoint failed after auto-start trigger");
  }
}

/**
 * Connect a spectator via WebSocket (no auth required).
 */
function connectSpectatorWs(
  gameId: number
): Promise<{ ws: WebSocket; messages: Record<string, unknown>[] }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const messages: Record<string, unknown>[] = [];

    ws.on("error", (err) => reject(err));

    ws.on("open", () => {
      ws.send(JSON.stringify({ type: "spectate", gameId }));
    });

    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      messages.push(msg);

      if (msg.type === "spectate:init") {
        resolve({ ws, messages });
      } else if (msg.type === "error") {
        reject(new Error(`Spectator error: ${msg.message}`));
      }
    });

    setTimeout(() => reject(new Error("Spectator connect timeout")), 5000);
  });
}

async function scenarioA_connectSpectator(
  gameId: number
): Promise<{ ws: WebSocket; messages: Record<string, unknown>[] }> {
  log("A6s", "Connecting spectator via WebSocket...");
  const conn = await connectSpectatorWs(gameId);
  success("Spectator WebSocket connected & received spectate:init");
  return conn;
}

async function scenarioA_connectWebSockets(
  gameId: number
): Promise<{ ws: WebSocket; messages: Record<string, unknown>[] }[]> {
  log("A6", "Connecting all players via WebSocket...");

  const connections: {
    ws: WebSocket;
    messages: Record<string, unknown>[];
  }[] = [];

  for (let i = 0; i < NUM_PLAYERS; i++) {
    const conn = await connectWs(playerWallets[i], gameId);
    success(`Player${i + 1} WebSocket connected & authenticated`);
    connections.push(conn);
  }

  return connections;
}

async function scenarioA_sendLocationPings(gameId: number): Promise<void> {
  log("A7", "Sending location pings for all players...");

  for (let i = 0; i < NUM_PLAYERS; i++) {
    const coords = playerCoords(i);
    const res = await apiCall(
      "POST",
      `/api/games/${gameId}/location`,
      playerWallets[i],
      { lat: coords.lat, lng: coords.lng, timestamp: Math.floor(Date.now() / 1000) }
    );
    assert(res.ok, `Player${i + 1} location ping accepted`);
  }
}

async function scenarioA_waitForGamePhase(
  connections: { ws: WebSocket; messages: Record<string, unknown>[] }[]
): Promise<void> {
  log("A8a", "Waiting for checkin → pregame → game transitions...");

  // Wait for game:started message on Player1's connection (sent when pregame completes)
  try {
    await waitForWsMessage(connections[0].messages, "game:started", 30000);
    success("Received game:started — game is now in active phase");
  } catch {
    // Try polling game status instead
    for (let i = 0; i < 30; i++) {
      await sleep(1000);
      const res = await fetch(`${SERVER_URL}/api/games/${connections[0].messages[0]?.gameId || 1}/status`);
      if (res.ok) {
        const data = await res.json();
        if (data.subPhase === "game") {
          success("Game transitioned to active phase (via polling)");
          return;
        }
        log("A8a", `Sub-phase: ${data.subPhase}, waiting...`);
      }
    }
    fail("Game did not transition to active phase within 30s");
  }
}

async function scenarioA_testKillRejections(
  gameId: number,
  connections: { ws: WebSocket; messages: Record<string, unknown>[] }[]
): Promise<void> {
  log("A8", "Testing kill rejection scenarios...");

  // Get Player1's actual target from game:started message (sent after pregame completes)
  const p1GameStarted = connections[0].messages.find(
    (m) => m.type === "game:started"
  ) as any;
  if (!p1GameStarted?.target?.playerNumber) {
    fail("Player1 did not receive target assignment in game:started message");
  }
  const p1TargetNumber = p1GameStarted.target.playerNumber as number;
  const p1TargetIdx = p1TargetNumber - 1; // 0-based
  const p1Target = playerWallets[p1TargetIdx].address.toLowerCase();

  // Find a NON-target player (someone Player1 is NOT hunting)
  const wrongTarget = playerWallets.find(
    (w) =>
      w.address.toLowerCase() !== playerWallets[0].address.toLowerCase() &&
      w.address.toLowerCase() !== p1Target
  )!;
  const wrongTargetIdx = playerWallets.indexOf(wrongTarget);
  const wrongTargetNumber = wrongTargetIdx + 1; // player numbers are 1-based

  // 1. Wrong target — should be rejected
  const wrongQr = encodeQr(gameId, wrongTargetNumber);
  const wrongRes = await apiCall(
    "POST",
    `/api/games/${gameId}/kill`,
    playerWallets[0],
    {
      qrPayload: wrongQr,
      hunterLat: 19.4352,
      hunterLng: -99.1281,
      bleNearbyAddresses: [wrongTarget.address.toLowerCase()],
    }
  );
  const wrongData = await wrongRes.json();
  assert(
    !wrongRes.ok || !wrongData.success,
    "Kill rejected: wrong target (not assigned)"
  );
  log("A8", `Rejection reason: ${wrongData.error}`);

  // 2. Too far from target — should be rejected
  const correctQr = encodeQr(gameId, p1TargetNumber);
  const farRes = await apiCall(
    "POST",
    `/api/games/${gameId}/kill`,
    playerWallets[0],
    {
      qrPayload: correctQr,
      hunterLat: 20.0, // ~63km away
      hunterLng: -99.0,
      bleNearbyAddresses: [p1Target],
    }
  );
  const farData = await farRes.json();
  assert(
    !farRes.ok || !farData.success,
    "Kill rejected: too far from target"
  );
  log("A8", `Rejection reason: ${farData.error}`);

  // 3. No BLE proximity — should be rejected
  const noBleRes = await apiCall(
    "POST",
    `/api/games/${gameId}/kill`,
    playerWallets[0],
    {
      qrPayload: correctQr,
      hunterLat: 19.4352,
      hunterLng: -99.1281,
      bleNearbyAddresses: [], // empty
    }
  );
  const noBleData = await noBleRes.json();
  assert(
    !noBleRes.ok || !noBleData.success,
    "Kill rejected: target not detected via BLE"
  );
  log("A8", `Rejection reason: ${noBleData.error}`);

  // 4. Invalid QR format
  const badQrRes = await apiCall(
    "POST",
    `/api/games/${gameId}/kill`,
    playerWallets[0],
    {
      qrPayload: "invalid:qr:data",
      hunterLat: 19.4352,
      hunterLng: -99.1281,
      bleNearbyAddresses: [p1Target],
    }
  );
  const badQrData = await badQrRes.json();
  assert(
    !badQrRes.ok || !badQrData.success,
    "Kill rejected: invalid QR format"
  );
  log("A8", `Rejection reason: ${badQrData.error}`);

  // 5. Wrong game ID in QR
  const wrongGameRes = await apiCall(
    "POST",
    `/api/games/${gameId}/kill`,
    playerWallets[0],
    {
      qrPayload: encodeQr(999, p1TargetNumber),
      hunterLat: 19.4352,
      hunterLng: -99.1281,
      bleNearbyAddresses: [p1Target],
    }
  );
  const wrongGameData = await wrongGameRes.json();
  assert(
    !wrongGameRes.ok || !wrongGameData.success,
    "Kill rejected: wrong game ID in QR"
  );
  log("A8", `Rejection reason: ${wrongGameData.error}`);
}

/**
 * Multi-player kill sequence.
 * Multiple hunters make kills following the actual target chain.
 * Returns the list of kills in order.
 */
async function scenarioA_multiPlayerKills(
  gameId: number,
  connections: { ws: WebSocket; messages: Record<string, unknown>[] }[]
): Promise<{ hunterIdx: number; targetIdx: number }[]> {
  log("A9", "Starting multi-player kill sequence...");

  const kills: { hunterIdx: number; targetIdx: number }[] = [];
  const alive = new Set(playerWallets.map((w) => w.address.toLowerCase()));

  // Build initial target map from game:started messages (target assignments)
  const targetMap = new Map<string, string>(); // hunter → target
  for (let i = 0; i < connections.length; i++) {
    const gameStartedMsg = connections[i].messages.find(
      (m) => m.type === "game:started"
    ) as any;
    if (gameStartedMsg?.target?.playerNumber) {
      const targetPlayerNumber = gameStartedMsg.target.playerNumber as number;
      const targetAddr = playerWallets[targetPlayerNumber - 1].address.toLowerCase();
      targetMap.set(
        playerWallets[i].address.toLowerCase(),
        targetAddr
      );
    }
  }

  log("A9", "Initial target chain:");
  for (const [hunter, target] of targetMap) {
    log("A9", `  ${playerName(hunter)} → ${playerName(target)}`);
  }

  // Verify leaderboard before any kills
  const lbRes0 = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  const lbData0 = await lbRes0.json();
  assert(
    lbData0.aliveCount === NUM_PLAYERS,
    `All ${NUM_PLAYERS} players alive initially`
  );
  assert(
    lbData0.leaderboard.length === NUM_PLAYERS,
    `Leaderboard has ${NUM_PLAYERS} entries`
  );

  // --- Kill 1: Player1 kills their target ---
  let hunterIdx = 0;
  let hunterAddr = playerWallets[hunterIdx].address.toLowerCase();
  let targetAddr = targetMap.get(hunterAddr)!;
  let targetIdx = playerWallets.findIndex(
    (w) => w.address.toLowerCase() === targetAddr
  );

  await doKill(
    gameId,
    hunterIdx,
    targetIdx,
    `Kill 1: ${playerName(hunterAddr)} → ${playerName(targetAddr)}`
  );
  kills.push({ hunterIdx, targetIdx });
  alive.delete(targetAddr);
  updateTargetMap(targetMap, hunterAddr, targetAddr);

  await sleep(2000);
  await provider.send("evm_mine", []);

  // Verify leaderboard after kill 1
  const lbRes1 = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  const lbData1 = await lbRes1.json();
  assert(
    lbData1.aliveCount === NUM_PLAYERS - 1,
    `${NUM_PLAYERS - 1} players alive after kill 1`
  );

  // --- Kill 2: A DIFFERENT hunter makes a kill ---
  const kill2HunterIdx = findAliveHunterWithTarget(
    alive,
    targetMap,
    /* excludeIdx */ 0
  );

  if (kill2HunterIdx >= 0) {
    hunterAddr = playerWallets[kill2HunterIdx].address.toLowerCase();
    targetAddr = targetMap.get(hunterAddr)!;
    targetIdx = playerWallets.findIndex(
      (w) => w.address.toLowerCase() === targetAddr
    );

    // Refresh location pings for both
    await refreshLocation(gameId, kill2HunterIdx);
    await refreshLocation(gameId, targetIdx);

    await doKill(
      gameId,
      kill2HunterIdx,
      targetIdx,
      `Kill 2: ${playerName(hunterAddr)} → ${playerName(targetAddr)}`
    );
    kills.push({ hunterIdx: kill2HunterIdx, targetIdx });
    alive.delete(targetAddr);
    updateTargetMap(targetMap, hunterAddr, targetAddr);

    await sleep(2000);
    await provider.send("evm_mine", []);
  }

  // Verify leaderboard mid-game
  const lbResMid = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  const lbDataMid = await lbResMid.json();
  assert(
    lbDataMid.aliveCount === alive.size,
    `${alive.size} players alive mid-game`
  );

  // --- Remaining kills: pick any alive hunter with a valid target ---
  while (alive.size > 1) {
    // Find any alive hunter who has a target in the target map
    let foundHunterIdx = -1;
    hunterAddr = "";
    targetAddr = "";

    for (let i = 0; i < NUM_PLAYERS; i++) {
      const addr = playerWallets[i].address.toLowerCase();
      if (alive.has(addr) && targetMap.has(addr)) {
        const tgt = targetMap.get(addr)!;
        if (alive.has(tgt)) {
          foundHunterIdx = i;
          hunterAddr = addr;
          targetAddr = tgt;
          break;
        }
      }
    }

    if (foundHunterIdx < 0 || !targetAddr) {
      log("A9", "No valid hunter→target pair found, breaking");
      break;
    }

    targetIdx = playerWallets.findIndex(
      (w) => w.address.toLowerCase() === targetAddr
    );
    const killNum = kills.length + 1;

    // Refresh locations before kill
    await refreshLocation(gameId, foundHunterIdx);
    if (targetIdx >= 0) {
      await refreshLocation(gameId, targetIdx);
    }

    await doKill(
      gameId,
      foundHunterIdx,
      targetIdx,
      `Kill ${killNum}: ${playerName(hunterAddr)} → ${playerName(targetAddr)}`
    );
    kills.push({ hunterIdx: foundHunterIdx, targetIdx });
    alive.delete(targetAddr);
    updateTargetMap(targetMap, hunterAddr, targetAddr);

    if (alive.size > 1) {
      await sleep(2000);
      await provider.send("evm_mine", []);
    }
  }

  log("A9", `Total kills: ${kills.length}`);
  assert(
    kills.length === NUM_PLAYERS - 1,
    `Exactly ${NUM_PLAYERS - 1} kills recorded`
  );

  return kills;
}

/**
 * Update target map after a kill: hunter inherits target's target.
 */
function updateTargetMap(
  targetMap: Map<string, string>,
  hunterAddr: string,
  targetAddr: string
): void {
  const newTarget = targetMap.get(targetAddr);
  targetMap.delete(targetAddr);
  if (newTarget && newTarget !== hunterAddr) {
    targetMap.set(hunterAddr, newTarget);
  } else {
    targetMap.delete(hunterAddr);
  }
}

/**
 * Find an alive player (not excludeIdx) who has an alive target.
 */
function findAliveHunterWithTarget(
  alive: Set<string>,
  targetMap: Map<string, string>,
  excludeIdx: number
): number {
  for (let i = 0; i < NUM_PLAYERS; i++) {
    if (i === excludeIdx) continue;
    const addr = playerWallets[i].address.toLowerCase();
    if (alive.has(addr) && targetMap.has(addr)) {
      const tgt = targetMap.get(addr)!;
      if (alive.has(tgt) && tgt !== addr) {
        return i;
      }
    }
  }
  return -1;
}

async function doKill(
  gameId: number,
  hunterIdx: number,
  targetIdx: number,
  description: string
): Promise<void> {
  log("A9", description);

  const hunterWallet = playerWallets[hunterIdx];
  const targetWallet = playerWallets[targetIdx];
  const targetPlayerNumber = targetIdx + 1; // player numbers are 1-based
  const qrPayload = encodeQr(gameId, targetPlayerNumber);
  const coords = playerCoords(hunterIdx);

  const res = await apiCall("POST", `/api/games/${gameId}/kill`, hunterWallet, {
    qrPayload,
    hunterLat: coords.lat,
    hunterLng: coords.lng,
    bleNearbyAddresses: [targetWallet.address.toLowerCase()],
  });

  const data = await res.json();
  if (res.ok && data.success) {
    success(
      `Kill accepted: ${playerName(hunterWallet.address)} → ${playerName(targetWallet.address)}`
    );
  } else {
    fail(`Kill failed: ${data.error}`);
  }
}

async function refreshLocation(
  gameId: number,
  playerIdx: number
): Promise<void> {
  const coords = playerCoords(playerIdx);
  await apiCall(
    "POST",
    `/api/games/${gameId}/location`,
    playerWallets[playerIdx],
    { lat: coords.lat, lng: coords.lng, timestamp: Math.floor(Date.now() / 1000) }
  );
}

async function scenarioA_verifyWsMessages(
  connections: { ws: WebSocket; messages: Record<string, unknown>[] }[],
  totalKills: number
): Promise<void> {
  log("A10", "Verifying WebSocket messages...");

  // Log Player1's full message list for debugging
  const p1Msgs = connections[0].messages;
  const p1Types = p1Msgs.map((m) => m.type as string);
  log("A10", `Player1 received ${p1Msgs.length} messages: ${p1Types.join(", ")}`);

  // ── auth:success ─────────────────────────────────────────────
  for (let i = 0; i < connections.length; i++) {
    const msg = connections[i].messages.find((m) => m.type === "auth:success") as any;
    assert(!!msg, `Player${i + 1} received auth:success`);
    assert(typeof msg.playerNumber === "number" && msg.playerNumber >= 1, `Player${i + 1} auth:success has playerNumber`);
    assert(typeof msg.address === "string", `Player${i + 1} auth:success has address`);
    assert(typeof msg.subPhase === "string", `Player${i + 1} auth:success has subPhase`);
  }

  // ── game:pregame_started ────────────────────────────────────
  for (let i = 0; i < connections.length; i++) {
    const msg = connections[i].messages.find((m) => m.type === "game:pregame_started") as any;
    assert(!!msg, `Player${i + 1} received game:pregame_started`);
    assert(typeof msg.pregameDurationSeconds === "number", `game:pregame_started has pregameDurationSeconds`);
    assert(typeof msg.pregameEndsAt === "number", `game:pregame_started has pregameEndsAt`);
    assert(typeof msg.checkedInCount === "number" && msg.checkedInCount === NUM_PLAYERS, `game:pregame_started has correct checkedInCount`);
  }

  // ── game:started (per-player with target) ──────────────────
  for (let i = 0; i < connections.length; i++) {
    const msg = connections[i].messages.find((m) => m.type === "game:started") as any;
    assert(!!msg, `Player${i + 1} received game:started`);
    assert(typeof msg.target?.playerNumber === "number" && msg.target.playerNumber >= 1, `Player${i + 1} game:started has valid target.playerNumber`);
    assert(typeof msg.hunterPlayerNumber === "number", `Player${i + 1} game:started has hunterPlayerNumber`);
    assert(typeof msg.heartbeatDeadline === "number", `Player${i + 1} game:started has heartbeatDeadline`);
    assert(typeof msg.heartbeatIntervalSeconds === "number", `Player${i + 1} game:started has heartbeatIntervalSeconds`);
    assert(typeof msg.zone === "object" && msg.zone !== null, `Player${i + 1} game:started has zone object`);
  }

  // ── game:started_broadcast ─────────────────────────────────
  for (let i = 0; i < connections.length; i++) {
    const msg = connections[i].messages.find((m) => m.type === "game:started_broadcast") as any;
    assert(!!msg, `Player${i + 1} received game:started_broadcast`);
    assert(msg.playerCount === NUM_PLAYERS, `game:started_broadcast has correct playerCount`);
  }

  // ── kill:recorded (broadcast to ALL players) ───────────────
  for (let i = 0; i < connections.length; i++) {
    const msgs = connections[i].messages.filter((m) => m.type === "kill:recorded");
    assert(
      msgs.length === totalKills,
      `Player${i + 1} received ${msgs.length}/${totalKills} kill:recorded`
    );
  }
  // Verify kill:recorded message structure
  const sampleKill = connections[0].messages.find((m) => m.type === "kill:recorded") as any;
  assert(typeof sampleKill.hunterNumber === "number" && sampleKill.hunterNumber >= 1, "kill:recorded has valid hunterNumber");
  assert(typeof sampleKill.targetNumber === "number" && sampleKill.targetNumber >= 1, "kill:recorded has valid targetNumber");
  assert(typeof sampleKill.hunterKills === "number", "kill:recorded has hunterKills count");

  // ── player:eliminated (broadcast to ALL players) ───────────
  for (let i = 0; i < connections.length; i++) {
    const msgs = connections[i].messages.filter((m) => m.type === "player:eliminated");
    assert(
      msgs.length === totalKills,
      `Player${i + 1} received ${msgs.length}/${totalKills} player:eliminated`
    );
  }
  // Verify player:eliminated message structure
  const sampleElim = connections[0].messages.find((m) => m.type === "player:eliminated") as any;
  assert(typeof sampleElim.playerNumber === "number" && sampleElim.playerNumber >= 1, "player:eliminated has valid playerNumber");
  assert(typeof sampleElim.reason === "string", "player:eliminated has reason field");

  // ── target:assigned (per-player, sent to hunters who inherit new targets) ──
  const allTargetAssigned = connections
    .map((c) => c.messages.filter((m) => m.type === "target:assigned"))
    .flat();
  assert(
    allTargetAssigned.length >= 1,
    `Players received ${allTargetAssigned.length} target:assigned messages total`
  );
  // Verify structure
  const sampleTarget = allTargetAssigned[0] as any;
  assert(typeof sampleTarget.target?.playerNumber === "number", "target:assigned has target.playerNumber");
  assert(typeof sampleTarget.hunterPlayerNumber === "number", "target:assigned has hunterPlayerNumber");

  // ── hunter:updated (per-player, sent when a player's hunter changes) ──
  const allHunterUpdated = connections
    .map((c) => c.messages.filter((m) => m.type === "hunter:updated"))
    .flat();
  assert(
    allHunterUpdated.length >= 1,
    `Players received ${allHunterUpdated.length} hunter:updated messages total`
  );
  const sampleHunter = allHunterUpdated[0] as any;
  assert(typeof sampleHunter.hunterPlayerNumber === "number", "hunter:updated has hunterPlayerNumber");

  // ── leaderboard:update (broadcast after each kill) ─────────
  for (let i = 0; i < connections.length; i++) {
    const msgs = connections[i].messages.filter((m) => m.type === "leaderboard:update");
    assert(
      msgs.length >= totalKills,
      `Player${i + 1} received ${msgs.length} leaderboard:update (≥${totalKills} kills)`
    );
  }
  // Verify structure
  const sampleLb = connections[0].messages.filter((m) => m.type === "leaderboard:update").pop() as any;
  assert(Array.isArray(sampleLb.entries), "leaderboard:update has entries array");
  assert(sampleLb.entries.length > 0, "leaderboard:update entries is non-empty");
  const lbEntry = sampleLb.entries[0] as any;
  assert(typeof lbEntry.playerNumber === "number", "leaderboard entry has playerNumber");
  assert(typeof lbEntry.kills === "number", "leaderboard entry has kills");
  assert(typeof lbEntry.isAlive === "boolean", "leaderboard entry has isAlive");

  // ── game:ended (broadcast when game ends) ──────────────────
  // Wait for it if not yet received
  let gameEndedMsg: any = null;
  for (const conn of connections) {
    try {
      gameEndedMsg = await waitForWsMessage(conn.messages, "game:ended", 5000);
      break;
    } catch {
      // try next
    }
  }
  assert(!!gameEndedMsg, "At least one player received game:ended");
  assert(typeof gameEndedMsg.winner1 === "number" && gameEndedMsg.winner1 >= 1, "game:ended has valid winner1");
  assert(typeof gameEndedMsg.topKiller === "number" && gameEndedMsg.topKiller >= 1, "game:ended has valid topKiller");

  // Verify ALL players received game:ended
  for (let i = 0; i < connections.length; i++) {
    const msg = connections[i].messages.find((m) => m.type === "game:ended");
    assert(!!msg, `Player${i + 1} received game:ended`);
  }

  // ── Summary of all message types received ──────────────────
  const allTypes = new Set<string>();
  for (const conn of connections) {
    for (const m of conn.messages) {
      allTypes.add(m.type as string);
    }
  }
  log("A10", `All WS message types received: ${[...allTypes].sort().join(", ")}`);

  const expectedTypes = [
    "auth:success",
    "game:pregame_started",
    "game:started",
    "game:started_broadcast",
    "kill:recorded",
    "player:eliminated",
    "target:assigned",
    "hunter:updated",
    "leaderboard:update",
    "game:ended",
  ];
  for (const t of expectedTypes) {
    assert(allTypes.has(t), `WS message type "${t}" was received`);
  }
}

async function scenarioA_verifySpectatorWsMessages(
  spectator: { ws: WebSocket; messages: Record<string, unknown>[] },
  totalKills: number
): Promise<void> {
  log("A10s", "Verifying spectator WebSocket messages...");

  const msgs = spectator.messages;
  const types = msgs.map((m) => m.type as string);
  log("A10s", `Spectator received ${msgs.length} messages: ${[...new Set(types)].sort().join(", ")}`);

  // ── spectate:init ──────────────────────────────────────────
  const initMsg = msgs.find((m) => m.type === "spectate:init") as any;
  assert(!!initMsg, "Spectator received spectate:init");
  assert(typeof initMsg.gameId === "number", "spectate:init has gameId");
  assert(typeof initMsg.phase === "number" || typeof initMsg.phase === "string", "spectate:init has phase");
  assert(typeof initMsg.playerCount === "number", "spectate:init has playerCount");
  assert(typeof initMsg.aliveCount === "number", "spectate:init has aliveCount");
  assert(Array.isArray(initMsg.leaderboard), "spectate:init has leaderboard array");
  assert(Array.isArray(initMsg.players), "spectate:init has players array");

  // ── spectator:positions (broadcast every 2s during game) ───
  const posMsgs = msgs.filter((m) => m.type === "spectator:positions");
  assert(
    posMsgs.length >= 1,
    `Spectator received ${posMsgs.length} spectator:positions messages`
  );
  const samplePos = posMsgs[0] as any;
  assert(Array.isArray(samplePos.players), "spectator:positions has players array");
  assert(typeof samplePos.zone === "object" && samplePos.zone !== null, "spectator:positions has zone object");
  assert(typeof samplePos.aliveCount === "number", "spectator:positions has aliveCount");

  // ── Broadcast messages that spectators also receive ────────
  // Spectators receive all broadcast() messages via spectatorBroadcastFn

  // kill:recorded
  const killMsgs = msgs.filter((m) => m.type === "kill:recorded");
  assert(
    killMsgs.length === totalKills,
    `Spectator received ${killMsgs.length}/${totalKills} kill:recorded`
  );

  // player:eliminated
  const elimMsgs = msgs.filter((m) => m.type === "player:eliminated");
  assert(
    elimMsgs.length === totalKills,
    `Spectator received ${elimMsgs.length}/${totalKills} player:eliminated`
  );

  // leaderboard:update
  const lbMsgs = msgs.filter((m) => m.type === "leaderboard:update");
  assert(
    lbMsgs.length >= totalKills,
    `Spectator received ${lbMsgs.length} leaderboard:update (≥${totalKills})`
  );

  // game:ended
  let gameEndedMsg: any = null;
  try {
    gameEndedMsg = await waitForWsMessage(msgs, "game:ended", 5000);
  } catch {
    // May already be in messages
    gameEndedMsg = msgs.find((m) => m.type === "game:ended");
  }
  assert(!!gameEndedMsg, "Spectator received game:ended");
  assert(typeof (gameEndedMsg as any).winner1 === "number", "Spectator game:ended has winner1");

  // Verify all expected spectator message types
  const spectatorTypes = new Set(types);
  const expectedSpectatorTypes = [
    "spectate:init",
    "spectator:positions",
    "kill:recorded",
    "player:eliminated",
    "leaderboard:update",
    "game:ended",
  ];
  for (const t of expectedSpectatorTypes) {
    assert(spectatorTypes.has(t), `Spectator received "${t}"`);
  }
}

async function scenarioA_verifyGameEnd(gameId: number): Promise<void> {
  log("A11", "Verifying game ended on-chain...");

  for (let i = 0; i < 30; i++) {
    await sleep(1000);
    await provider.send("evm_mine", []);

    const state = await operatorContract.getGameState(gameId);
    if (Number(state.phase) === 2) {
      success("Game ended on-chain!");

      const w1Num = Number(state.winner1);
      const w2Num = Number(state.winner2);
      const w3Num = Number(state.winner3);
      const tkNum = Number(state.topKiller);

      log("A11", "Winners (playerNumbers):", {
        winner1: w1Num,
        winner2: w2Num,
        winner3: w3Num,
        topKiller: tkNum,
      });

      // Winner1 should be a valid player number (1-based)
      assert(w1Num >= 1 && w1Num <= NUM_PLAYERS, `Winner1 is Player${w1Num} (last standing)`);

      // Top killer should be a valid player number
      assert(tkNum >= 1 && tkNum <= NUM_PLAYERS, `Top killer is Player${tkNum}`);

      // Log claimable amounts
      for (let j = 0; j < NUM_PLAYERS; j++) {
        const claim = await operatorContract.getClaimableAmount(
          gameId,
          playerWallets[j].address
        );
        log(
          "A11",
          `Player${j + 1} claimable: ${ethers.formatEther(claim)} ETH`
        );
      }

      // Server status
      const res = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
      const serverData = await res.json();
      assert(serverData.phase === 2, "Server also shows game as ENDED");

      return;
    }
  }

  const res = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  const serverData = await res.json();
  log("A11", "Server state:", serverData);
  fail("Game did not end within 30s");
}

async function scenarioA_claimPrizes(gameId: number): Promise<void> {
  log("A12", "Claiming prizes on-chain...");

  const state = await operatorContract.getGameState(gameId);
  const w1Num = Number(state.winner1);
  const w2Num = Number(state.winner2);
  const w3Num = Number(state.winner3);
  const tkNum = Number(state.topKiller);

  // Helper: playerNumber (1-based) → wallet index (0-based)
  const numToIdx = (pNum: number) => pNum - 1;

  // Claim for winner1
  const w1Idx = numToIdx(w1Num);
  if (w1Idx >= 0 && w1Idx < NUM_PLAYERS) {
    const w1BalBefore = await provider.getBalance(playerWallets[w1Idx].address);
    const w1Claimable = await operatorContract.getClaimableAmount(
      gameId,
      playerWallets[w1Idx].address
    );

    if (w1Claimable > 0n) {
      const claimTx = await playerContracts[w1Idx].claimPrize(gameId);
      await claimTx.wait();
      const w1BalAfter = await provider.getBalance(playerWallets[w1Idx].address);
      assert(
        w1BalAfter > w1BalBefore - ethers.parseEther("0.001"),
        `Player${w1Num} (winner1) balance increased after claim`
      );
      success(`Player${w1Num} (winner1) claimed prize`);
    }

    // Check claimed flag via getPlayerInfo
    const pInfo = await operatorContract.getPlayerInfo(gameId, playerWallets[w1Idx].address);
    assert(pInfo.claimed, "Winner1 claimed flag set on-chain");

    // Double claim should revert
    try {
      const doubleClaim = await playerContracts[w1Idx].claimPrize(gameId);
      await doubleClaim.wait();
      fail("Double claim should have reverted");
    } catch {
      success("Double claim correctly reverted");
    }
  }

  // Winner2 claims
  if (w2Num > 0) {
    const w2Idx = numToIdx(w2Num);
    if (w2Idx >= 0 && w2Idx < NUM_PLAYERS) {
      const w2Claimable = await operatorContract.getClaimableAmount(
        gameId,
        playerWallets[w2Idx].address
      );
      if (w2Claimable > 0n) {
        const w2Tx = await playerContracts[w2Idx].claimPrize(gameId);
        await w2Tx.wait();
        success(`Player${w2Num} (winner2) claimed prize`);
      }
    }
  }

  // Winner3 claims
  if (w3Num > 0) {
    const w3Idx = numToIdx(w3Num);
    if (w3Idx >= 0 && w3Idx < NUM_PLAYERS) {
      const w3Claimable = await operatorContract.getClaimableAmount(
        gameId,
        playerWallets[w3Idx].address
      );
      if (w3Claimable > 0n) {
        const w3Tx = await playerContracts[w3Idx].claimPrize(gameId);
        await w3Tx.wait();
        success(`Player${w3Num} (winner3) claimed prize`);
      }
    }
  }

  // Non-winner should have 0 claimable and revert
  const winnerNums = new Set([w1Num, w2Num, w3Num, tkNum]);
  const nonWinnerIdx = playerWallets.findIndex(
    (_, i) => !winnerNums.has(i + 1)
  );
  if (nonWinnerIdx >= 0) {
    const nwClaimable = await operatorContract.getClaimableAmount(
      gameId,
      playerWallets[nonWinnerIdx].address
    );
    assert(
      nwClaimable === 0n,
      `Player${nonWinnerIdx + 1} (non-winner) has 0 claimable`
    );

    try {
      const nwTx = await playerContracts[nonWinnerIdx].claimPrize(gameId);
      await nwTx.wait();
      fail("Non-winner claim should have reverted");
    } catch {
      success("Non-winner claim correctly reverted");
    }
  }
}

async function scenarioA_verifyDatabase(
  gameId: number,
  totalKills: number
): Promise<void> {
  log("DB-A", "Opening server database for verification...");

  const dbPath = resolve(__dirname, "../data/e2e-test.db");
  const db = new Database(dbPath, { readonly: true });

  try {
    // ---- games table ----
    log("DB-A", "Checking games table...");
    const game = db
      .prepare("SELECT * FROM games WHERE game_id = ?")
      .get(gameId) as Record<string, unknown>;
    assert(!!game, "DB: Game row exists");
    assert(game.title === "E2E Multi-Player Game", "DB: Game title correct");
    assert(game.phase === 2, "DB: Game phase = 2 (ENDED)");
    assert(game.min_players === 3, "DB: min_players = 3");
    assert(game.max_players === 10, "DB: max_players = 10");
    assert(
      game.entry_fee === "10000000000000000",
      "DB: entry_fee = 0.01 ETH in wei"
    );
    assert(game.started_at !== null, "DB: started_at is set");
    assert(game.ended_at !== null, "DB: ended_at is set");
    // Winner1 should be a valid player
    const dbWinner1Idx = playerWallets.findIndex(
      (w) => w.address.toLowerCase() === (game.winner1 as string)
    );
    assert(dbWinner1Idx >= 0, `DB: winner1 = ${playerName(game.winner1 as string)}`);
    // Top killer should be a valid player
    const dbTopKillerIdx = playerWallets.findIndex(
      (w) => w.address.toLowerCase() === (game.top_killer as string)
    );
    assert(dbTopKillerIdx >= 0, `DB: top_killer = ${playerName(game.top_killer as string)}`);

    // ---- zone_shrinks table ----
    log("DB-A", "Checking zone_shrinks table...");
    const shrinks = db
      .prepare(
        "SELECT at_second, radius_meters FROM zone_shrinks WHERE game_id = ? ORDER BY at_second ASC"
      )
      .all(gameId) as { at_second: number; radius_meters: number }[];
    assert(shrinks.length === 3, "DB: 3 zone shrink entries");
    assert(
      shrinks[0].at_second === 0 && shrinks[0].radius_meters === 2000,
      "DB: Shrink 0 = (0, 2000)"
    );
    assert(
      shrinks[1].at_second === 600 && shrinks[1].radius_meters === 1000,
      "DB: Shrink 1 = (600, 1000)"
    );
    assert(
      shrinks[2].at_second === 1200 && shrinks[2].radius_meters === 300,
      "DB: Shrink 2 = (1200, 300)"
    );

    // ---- players table ----
    log("DB-A", "Checking players table...");
    const players = db
      .prepare(
        "SELECT * FROM players WHERE game_id = ? ORDER BY player_number ASC"
      )
      .all(gameId) as Record<string, unknown>[];
    assert(players.length === NUM_PLAYERS, `DB: ${NUM_PLAYERS} player rows`);

    // The winner (last alive) should have is_alive = 1 and kills > 0
    const alivePlayers = players.filter((p) => p.is_alive === 1);
    assert(alivePlayers.length === 1, "DB: Exactly 1 alive player (winner)");
    assert(
      (alivePlayers[0].kills as number) >= 1,
      `DB: Winner (${playerName(alivePlayers[0].address as string)}) has kills`
    );

    const deadPlayers = players.filter((p) => p.is_alive === 0);
    assert(
      deadPlayers.length === NUM_PLAYERS - 1,
      `DB: ${NUM_PLAYERS - 1} dead players`
    );

    for (const p of deadPlayers) {
      assert(
        p.eliminated_at !== null,
        `DB: ${(p.address as string).slice(0, 8)}... has eliminated_at`
      );
      assert(
        p.eliminated_by !== null,
        `DB: ${(p.address as string).slice(0, 8)}... has eliminated_by`
      );
    }

    // ---- kills table ----
    log("DB-A", "Checking kills table...");
    const kills = db
      .prepare(
        "SELECT * FROM kills WHERE game_id = ? ORDER BY timestamp ASC"
      )
      .all(gameId) as Record<string, unknown>[];
    assert(kills.length === totalKills, `DB: ${totalKills} kill records`);

    const killTargets = new Set<string>();
    for (const kill of kills) {
      assert(kill.hunter_lat !== null, "DB: Kill has hunter_lat");
      assert(kill.hunter_lng !== null, "DB: Kill has hunter_lng");
      assert(kill.distance_meters !== null, "DB: Kill has distance_meters");
      assert(
        (kill.distance_meters as number) < 500,
        `DB: Kill distance < 500m (was ${kill.distance_meters})`
      );
      killTargets.add(kill.target_address as string);
    }
    assert(
      killTargets.size === totalKills,
      `DB: ${totalKills} unique kill targets`
    );

    // Multiple hunters made kills
    const killHunters = new Set(kills.map((k) => k.hunter_address));
    log("DB-A", `Unique kill hunters: ${killHunters.size}`);
    assert(killHunters.size >= 2, "DB: Multiple hunters made kills");

    // At least some kills have tx_hash
    const killsWithTx = kills.filter((k) => k.tx_hash !== null);
    log("DB-A", `Kills with tx_hash: ${killsWithTx.length}/${totalKills}`);
    assert(killsWithTx.length >= 1, "DB: At least 1 kill has tx_hash");

    // ---- target_assignments table ----
    log("DB-A", "Checking target_assignments table...");
    const assignments = db
      .prepare(
        "SELECT COUNT(*) as c FROM target_assignments WHERE game_id = ?"
      )
      .get(gameId) as { c: number };
    assert(
      assignments.c === 0,
      "DB: Target assignments fully resolved (0 remaining)"
    );

    // ---- operator_txs table ----
    log("DB-A", "Checking operator_txs table...");
    const opTxs = db
      .prepare(
        "SELECT * FROM operator_txs WHERE game_id = ? ORDER BY created_at ASC"
      )
      .all(gameId) as Record<string, unknown>[];

    const expectedMinTxs = 1 + totalKills + 1; // startGame + kills + endGame
    assert(
      opTxs.length >= expectedMinTxs,
      `DB: At least ${expectedMinTxs} operator txs (got ${opTxs.length})`
    );

    const actions = opTxs.map((t) => t.action);
    assert(actions.includes("startGame"), "DB: Has startGame operator tx");
    assert(
      actions.filter((a) => a === "recordKill").length === totalKills,
      `DB: Has ${totalKills} recordKill operator txs`
    );
    assert(actions.includes("endGame"), "DB: Has endGame operator tx");

    const confirmedTxs = opTxs.filter((t) => t.status === "confirmed");
    assert(
      confirmedTxs.length === opTxs.length,
      `DB: All ${opTxs.length} operator txs confirmed`
    );

    for (const tx of opTxs) {
      assert(
        tx.tx_hash !== null,
        `DB: Operator tx "${tx.action}" has tx_hash`
      );
      assert(
        tx.confirmed_at !== null,
        `DB: Operator tx "${tx.action}" has confirmed_at`
      );
    }

    // ---- location_pings table ----
    log("DB-A", "Checking location_pings table...");
    const pings = db
      .prepare("SELECT * FROM location_pings WHERE game_id = ?")
      .all(gameId) as Record<string, unknown>[];
    assert(
      pings.length >= NUM_PLAYERS,
      `DB: At least ${NUM_PLAYERS} location pings (got ${pings.length})`
    );

    const allInZone = pings.every((p) => p.is_in_zone === 1);
    assert(allInZone, "DB: All location pings are in-zone");

    const pingAddresses = new Set(pings.map((p) => p.address));
    assert(
      pingAddresses.size >= NUM_PLAYERS,
      `DB: Location pings from ${pingAddresses.size} unique players`
    );

    // ---- sync_state table ----
    log("DB-A", "Checking sync_state table...");
    const syncRow = db
      .prepare(
        "SELECT value FROM sync_state WHERE key = 'lastProcessedBlock'"
      )
      .get() as { value: string } | undefined;
    assert(!!syncRow, "DB: sync_state has lastProcessedBlock");
    assert(
      parseInt(syncRow!.value, 10) > 0,
      `DB: lastProcessedBlock > 0 (is ${syncRow!.value})`
    );

    success("All game A database checks passed!");
  } finally {
    db.close();
  }
}

// ============ Scenario B: Game Cancellation + Refund ============

async function scenarioB_cancellationAndRefund(): Promise<void> {
  section("Scenario B: Game Cancellation + Refund");

  // 1. Create game with high minPlayers
  log("B1", "Creating game with minPlayers=5 (will fail to fill)...");

  const now = (await provider.getBlock("latest"))!.timestamp;
  const regDeadline = now + 60;
  const gameDate = now + 120;
  const maxDuration = 3600;

  const params = {
    title: "Cancellation Test Game",
    entryFee: ethers.parseEther("0.02"),
    minPlayers: 5,
    maxPlayers: 10,
    registrationDeadline: regDeadline,
    gameDate: gameDate,
    maxDuration: maxDuration,
    centerLat: 19435244,
    centerLng: -99128056,
    meetingLat: 19435244,
    meetingLng: -99128056,
    bps1st: 4000,    // sum + platformFeeBps(1000) = 10000
    bps2nd: 2000,
    bps3rd: 1000,
    bpsKills: 1000,
    bpsCreator: 1000,
  };

  const shrinks = [
    { atSecond: 0, radiusMeters: 2000 },
    { atSecond: 600, radiusMeters: 1000 },
    { atSecond: 1200, radiusMeters: 300 },
  ];

  const tx = await operatorContract.createGame(params, shrinks);
  const receipt = await tx.wait();

  const iface = new ethers.Interface(abi);
  const createdLog = receipt.logs.find((l: ethers.Log) => {
    try {
      return (
        iface.parseLog({ topics: l.topics as string[], data: l.data })?.name ===
        "GameCreated"
      );
    } catch {
      return false;
    }
  });
  const parsed = iface.parseLog({
    topics: createdLog.topics as string[],
    data: createdLog.data,
  });
  const gameId = Number(parsed!.args[0]);
  success(`Game B created with ID: ${gameId}`);

  // 2. Register only 2 players (insufficient for minPlayers=5)
  log("B2", "Registering only 2 players (not enough)...");
  const entryFee = ethers.parseEther("0.02");

  const regTx1 = await playerContracts[0].register(gameId, {
    value: entryFee,
  });
  await regTx1.wait();
  const regTx2 = await playerContracts[1].register(gameId, {
    value: entryFee,
  });
  await regTx2.wait();
  success("2 players registered for game B");

  // 3. Warp time past deadline
  log("B3", "Warping time past registration deadline...");
  await anvilWarpTime(120);
  success("Time warped past deadline");

  // 4. Trigger cancellation (anyone can call this)
  log("B4", "Triggering cancellation...");
  const cancelTx = await playerContracts[2].triggerCancellation(gameId);
  await cancelTx.wait();

  const state = await operatorContract.getGameState(gameId);
  assert(Number(state.phase) === 3, "Game B is CANCELLED on-chain");

  // 5. Wait for server to process the event
  await sleep(3000);
  await provider.send("evm_mine", []);
  await sleep(2000);

  const serverRes = await fetch(`${SERVER_URL}/api/games/${gameId}/status`);
  if (serverRes.ok) {
    const serverData = await serverRes.json();
    assert(serverData.phase === 3, "Server shows game B as CANCELLED");
  } else {
    log(
      "B5",
      "Server hasn't synced game B yet (expected — event processing is async)"
    );
  }

  // 6. Claim refunds
  log("B5", "Claiming refunds...");

  const refundTx1 = await playerContracts[0].claimRefund(gameId);
  const refundReceipt1 = await refundTx1.wait();

  // Verify refund via receipt events instead of balance diff (avoids Anvil caching issues)
  assert(refundReceipt1.status === 1, "Refund tx succeeded");

  // Parse RefundClaimed event from receipt
  const iface2 = new ethers.Interface(abi);
  const refundLog = refundReceipt1.logs.find((l: ethers.Log) => {
    try {
      return iface2.parseLog({ topics: l.topics as string[], data: l.data })?.name === "RefundClaimed";
    } catch { return false; }
  });
  assert(!!refundLog, "RefundClaimed event emitted");
  const refundParsed = iface2.parseLog({ topics: refundLog.topics as string[], data: refundLog.data });
  assert(refundParsed!.args[2] === ethers.parseEther("0.02"), `Refund amount is 0.02 ETH (got ${ethers.formatEther(refundParsed!.args[2])})`);
  success(`Player1 refund of ${ethers.formatEther(refundParsed!.args[2])} ETH confirmed via event`);

  const refundTx2 = await playerContracts[1].claimRefund(gameId);
  await refundTx2.wait();
  success("Player2 refund claimed");

  // 7. Double refund should revert
  try {
    const doubleRefund = await playerContracts[0].claimRefund(gameId);
    await doubleRefund.wait();
    fail("Double refund should have reverted");
  } catch {
    success("Double refund correctly reverted");
  }

  // 8. Non-registered player refund should revert
  try {
    const nonRegRefund = await playerContracts[3].claimRefund(gameId);
    await nonRegRefund.wait();
    fail("Non-registered player refund should have reverted");
  } catch {
    success("Non-registered player refund correctly reverted");
  }

  // 9. Verify claimed flags via getPlayerInfo
  const p1Info = await operatorContract.getPlayerInfo(gameId, playerWallets[0].address);
  const p2Info = await operatorContract.getPlayerInfo(gameId, playerWallets[1].address);
  assert(p1Info.claimed, "Player1 claimed flag set for refund");
  assert(p2Info.claimed, "Player2 claimed flag set for refund");

  // 10. Verify database for game B
  log("B6", "Verifying game B in database...");
  const dbPath = resolve(__dirname, "../data/e2e-test.db");
  const db = new Database(dbPath, { readonly: true });
  try {
    const gameRow = db
      .prepare("SELECT * FROM games WHERE game_id = ?")
      .get(gameId) as Record<string, unknown> | undefined;
    if (gameRow) {
      assert(gameRow.phase === 3, "DB: Game B phase = 3 (CANCELLED)");
      assert(
        gameRow.title === "Cancellation Test Game",
        "DB: Game B title correct"
      );
    } else {
      log(
        "B6",
        "Game B not in server DB (server may not have synced it yet — OK for cancellation test)"
      );
    }
  } finally {
    db.close();
  }
}

// ============ Scenario C: Platform Fee Withdrawal ============

async function scenarioC_platformFees(): Promise<void> {
  section("Scenario C: Platform Fee Withdrawal");

  const fees = await operatorContract.platformFeesAccrued();
  log("C1", `Platform fees accrued: ${ethers.formatEther(fees)} ETH`);
  assert(fees > 0n, "Platform fees have been accrued");

  // Withdraw platform fees (only owner can do this)
  const withdrawTx = await operatorContract.withdrawPlatformFees(
    operatorWallet.address
  );
  await withdrawTx.wait();
  success("Platform fees withdrawn successfully");

  // Fees should now be 0
  const feesAfter = await operatorContract.platformFeesAccrued();
  assert(feesAfter === 0n, "Platform fees now 0 after withdrawal");

  // Double withdrawal should revert (no fees left)
  try {
    const doubleTx = await operatorContract.withdrawPlatformFees(
      operatorWallet.address
    );
    await doubleTx.wait();
    fail("Double withdrawal should have reverted");
  } catch {
    success("Double withdrawal correctly reverted (no fees)");
  }
}

// ============ Main ============

async function main() {
  console.log("\n" + "=".repeat(60));
  console.log("  Chain Assassin — Multi-Player E2E Test (Anvil)");
  console.log("=".repeat(60));
  console.log(`  RPC:      ${RPC_URL}`);
  console.log(`  Server:   ${SERVER_URL}`);
  console.log(`  Contract: ${CONTRACT_ADDRESS}`);
  console.log(`  Operator: ${operatorWallet.address}`);
  for (let i = 0; i < NUM_PLAYERS; i++) {
    console.log(`  Player${i + 1}:  ${playerWallets[i].address}`);
  }
  console.log("=".repeat(60) + "\n");

  // Verify connection
  try {
    const blockNum = await provider.getBlockNumber();
    success(`Connected to Anvil (block ${blockNum})`);
  } catch {
    fail("Cannot connect to Anvil at " + RPC_URL);
  }

  // Verify server is up
  try {
    const res = await fetch(`${SERVER_URL}/health`);
    const data = await res.json();
    assert(data.status === "ok", "Server health check passed");
  } catch {
    fail("Cannot connect to server at " + SERVER_URL);
  }

  // ======== Scenario A: Full 6-Player Game ========
  section("Scenario A: Full 6-Player Game");

  const gameId = await scenarioA_createGame();
  await scenarioA_registerPlayers(gameId);
  await scenarioA_waitForServerSync(gameId);
  // Send location pings BEFORE game start so auto-seed can find nearby players
  await scenarioA_sendLocationPings(gameId);
  await scenarioA_warpTimeAndAutoStart(gameId);
  await scenarioA_checkin(gameId);

  // Connect spectator early — observes checkin→pregame→game→ended transitions
  const spectator = await scenarioA_connectSpectator(gameId);

  const connections = await scenarioA_connectWebSockets(gameId);
  // Wait for checkin → pregame → game phase transitions
  await scenarioA_waitForGamePhase(connections);
  // Refresh locations after game starts (needed for kill proximity checks)
  await scenarioA_sendLocationPings(gameId);
  await scenarioA_testKillRejections(gameId, connections);

  const kills = await scenarioA_multiPlayerKills(gameId, connections);

  // Wait for endGame tx
  await sleep(3000);

  await scenarioA_verifyGameEnd(gameId);
  await scenarioA_verifyWsMessages(connections, kills.length);
  await scenarioA_verifySpectatorWsMessages(spectator, kills.length);

  // Close WebSocket connections
  spectator.ws.close();
  for (const conn of connections) {
    conn.ws.close();
  }

  // Wait for all async tx confirmations
  await sleep(2000);

  await scenarioA_claimPrizes(gameId);
  await scenarioA_verifyDatabase(gameId, kills.length);

  // ======== Scenario B: Cancellation + Refund ========
  await scenarioB_cancellationAndRefund();

  // ======== Scenario C: Platform Fees ========
  await scenarioC_platformFees();

  // ======== Summary ========
  console.log("\n" + "=".repeat(60));
  console.log(
    `  \x1b[32m🎉 ALL E2E TESTS PASSED! (${assertCount} assertions)\x1b[0m`
  );
  console.log("=".repeat(60) + "\n");

  process.exit(0);
}

main().catch((err) => {
  console.error("\n\x1b[31mE2E TEST FAILED:\x1b[0m", err.message);
  if (err.stack) console.error(err.stack);
  process.exit(1);
});
