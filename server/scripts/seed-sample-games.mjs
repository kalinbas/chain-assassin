import { ethers } from "ethers";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

const rpcUrl = process.env.RPC_URL || "http://127.0.0.1:8545";
const contractAddress = process.env.CONTRACT_ADDRESS;
const operatorKey =
  process.env.OPERATOR_PRIVATE_KEY ||
  "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

if (!contractAddress) {
  console.error("Missing CONTRACT_ADDRESS");
  process.exit(1);
}

const ANVIL_KEYS = [
  "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
  "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
  "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a",
  "0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6",
  "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a",
  "0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba",
  "0x92db14e403b83dfe3df233f83dfa3a0d7096f21ca9b0d6d6b8d88b2b4ec1564e",
];

const abiPath = resolve(__dirname, "../abi/ChainAssassin.json");
const abi = JSON.parse(readFileSync(abiPath, "utf-8")).abi;

const provider = new ethers.JsonRpcProvider(rpcUrl);
const operator = new ethers.Wallet(operatorKey, provider);
const contract = new ethers.Contract(contractAddress, abi, operator);
const playerWallets = ANVIL_KEYS.slice(1).map((k) => new ethers.Wallet(k, provider));
const playerContracts = playerWallets.map((wallet) => new ethers.Contract(contractAddress, abi, wallet));
let operatorNonce = null;

function contractCoord(deg) {
  return Math.round(deg * 1_000_000);
}

async function advanceToTimestamp(targetTs) {
  const latest = await provider.getBlock("latest");
  const currentTs = latest?.timestamp ?? Math.floor(Date.now() / 1000);
  if (targetTs <= currentTs) {
    return currentTs;
  }

  await provider.send("evm_increaseTime", [targetTs - currentTs]);
  await provider.send("evm_mine", []);
  return targetTs;
}

function isNonceRace(err) {
  const message = String(err?.message || "");
  return err?.code === "NONCE_EXPIRED" || message.includes("nonce too low");
}

async function sendWalletTx(wallet, send) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const nonce = await provider.getTransactionCount(wallet.address, "pending");
    try {
      return await send(nonce);
    } catch (err) {
      if (isNonceRace(err) && attempt < 3) {
        continue;
      }
      throw err;
    }
  }

  throw new Error("Unable to send wallet transaction after nonce retries");
}

async function sendOperatorTx(send) {
  for (let attempt = 0; attempt < 6; attempt++) {
    if (operatorNonce == null) {
      operatorNonce = await provider.getTransactionCount(operator.address, "pending");
    }
    try {
      const tx = await send(operatorNonce);
      operatorNonce += 1;
      return tx;
    } catch (err) {
      if (isNonceRace(err) && attempt < 5) {
        operatorNonce = await provider.getTransactionCount(operator.address, "pending");
        continue;
      }
      throw err;
    }
  }

  throw new Error("Unable to send operator transaction after nonce retries");
}

async function createGame(sample, now) {
  const params = {
    title: sample.title,
    entryFee: sample.entryFee,
    minPlayers: sample.minPlayers,
    maxPlayers: sample.maxPlayers,
    registrationDeadline: now + sample.registrationDelaySec,
    gameDate: now + sample.gameDateDelaySec,
    maxDuration: sample.maxDurationSec,
    centerLat: contractCoord(sample.centerLat),
    centerLng: contractCoord(sample.centerLng),
    meetingLat: contractCoord(sample.centerLat),
    meetingLng: contractCoord(sample.centerLng),
    bps1st: 4000,
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

  const tx = await sendOperatorTx((nonce) => contract.createGame(params, shrinks, { nonce }));
  const receipt = await tx.wait();
  const iface = new ethers.Interface(abi);

  const createdLog = receipt.logs.find((l) => {
    try {
      return iface.parseLog({ topics: l.topics, data: l.data })?.name === "GameCreated";
    } catch {
      return false;
    }
  });

  if (!createdLog) {
    throw new Error(`GameCreated event missing for "${sample.title}"`);
  }

  const parsed = iface.parseLog({
    topics: createdLog.topics,
    data: createdLog.data,
  });
  const gameId = Number(parsed.args[0]);
  return { gameId, params };
}

async function registerPlayers(gameId, entryFee, playerIndexes) {
  for (const idx of playerIndexes) {
    const p = playerContracts[idx];
    const wallet = playerWallets[idx];
    const tx = await sendWalletTx(wallet, (nonce) => p.register(gameId, { value: entryFee, nonce }));
    await tx.wait();
  }
}

async function createGames(samples, now) {
  const created = [];
  for (const sample of samples) {
    const { gameId, params } = await createGame(sample, now);
    await registerPlayers(gameId, params.entryFee, sample.players);
    created.push({
      gameId,
      title: sample.title,
      expectedPhase: sample.outcome ?? "registration",
      minPlayers: sample.minPlayers,
      registeredPlayers: sample.players.length,
      registrationDeadline: params.registrationDeadline,
      gameDate: params.gameDate,
    });
  }
  return created;
}

async function finalizeHistoricalGames(games) {
  if (games.length === 0) return;

  const targetTs = Math.max(...games.map((g) => g.gameDate)) + 30;
  await advanceToTimestamp(targetTs);

  for (const game of games) {
    if (game.expectedPhase === "cancelled") {
      const tx = await sendOperatorTx((nonce) => contract.triggerCancellation(game.gameId, { nonce }));
      await tx.wait();
      continue;
    }

    const startTx = await sendOperatorTx((nonce) => contract.startGame(game.gameId, { nonce }));
    await startTx.wait();

    // Winners are based on registration order for deterministic local fixtures.
    const endTx = await sendOperatorTx((nonce) => contract.endGame(game.gameId, 1, 2, 3, 1, { nonce }));
    await endTx.wait();
  }
}

async function main() {
  const latest = await provider.getBlock("latest");
  const now = Math.max(latest?.timestamp ?? 0, Math.floor(Date.now() / 1000));

  const historicalSamples = [
    {
      title: "Harbor Showdown (Archive)",
      centerLat: 40.7060,
      centerLng: -74.0086,
      entryFee: ethers.parseEther("0.01"),
      minPlayers: 3,
      maxPlayers: 8,
      registrationDelaySec: 300,
      gameDateDelaySec: 420,
      maxDurationSec: 1800,
      players: [0, 1, 2],
      outcome: "ended",
    },
    {
      title: "Parkline Clash (Archive)",
      centerLat: 34.0096,
      centerLng: -118.4976,
      entryFee: ethers.parseEther("0.015"),
      minPlayers: 3,
      maxPlayers: 10,
      registrationDelaySec: 330,
      gameDateDelaySec: 480,
      maxDurationSec: 2400,
      players: [2, 3, 4],
      outcome: "ended",
    },
    {
      title: "Bridge Blitz (Archive)",
      centerLat: 51.5074,
      centerLng: -0.1278,
      entryFee: ethers.parseEther("0.02"),
      minPlayers: 4,
      maxPlayers: 12,
      registrationDelaySec: 360,
      gameDateDelaySec: 540,
      maxDurationSec: 2400,
      players: [0, 5],
      outcome: "cancelled",
    },
  ];

  const upcomingSamples = [
    {
      title: "Downtown Warmup",
      centerLat: 37.7749,
      centerLng: -122.4194,
      entryFee: ethers.parseEther("0.01"),
      minPlayers: 3,
      maxPlayers: 10,
      registrationDelaySec: 1800,
      gameDateDelaySec: 3600,
      maxDurationSec: 5400,
      players: [0, 1, 2],
    },
    {
      title: "Riverside Rush",
      centerLat: 34.0522,
      centerLng: -118.2437,
      entryFee: ethers.parseEther("0.02"),
      minPlayers: 4,
      maxPlayers: 12,
      registrationDelaySec: 2700,
      gameDateDelaySec: 4500,
      maxDurationSec: 7200,
      players: [3, 4],
    },
    {
      title: "Night Ops",
      centerLat: 40.7128,
      centerLng: -74.006,
      entryFee: ethers.parseEther("0.005"),
      minPlayers: 3,
      maxPlayers: 8,
      registrationDelaySec: 3600,
      gameDateDelaySec: 5400,
      maxDurationSec: 7200,
      players: [5],
    },
  ];

  const historicalGames = await createGames(historicalSamples, now);
  await finalizeHistoricalGames(historicalGames);

  const refreshed = await provider.getBlock("latest");
  const upcomingNow = refreshed?.timestamp ?? now;
  const upcomingGames = await createGames(upcomingSamples, upcomingNow);

  console.log(JSON.stringify({ historicalGames, upcomingGames }, null, 2));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
