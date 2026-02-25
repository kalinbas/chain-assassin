import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

describe("rebuild from chain ordering", () => {
  let tempDir = "";
  let listener: typeof import("../src/blockchain/listener.js");
  let queries: typeof import("../src/db/queries.js");

  beforeEach(async () => {
    tempDir = mkdtempSync(join(tmpdir(), "chain-assassin-rebuild-ordering-"));

    process.env.DB_PATH = join(tempDir, "test.db");
    process.env.RPC_URL = "http://127.0.0.1:8545";
    process.env.RPC_WS_URL = "ws://127.0.0.1:8545";
    process.env.CONTRACT_ADDRESS = "0x0000000000000000000000000000000000000001";
    process.env.OPERATOR_PRIVATE_KEY = `0x${"11".repeat(32)}`;
    process.env.START_GAME_ID = "1";

    const chainPlayers = new Map([
      [
        1,
        {
          addr: "0x1000000000000000000000000000000000000001",
          killedAt: 1000,
          claimed: false,
          killCount: 3,
        },
      ],
      [
        2,
        {
          addr: "0x2000000000000000000000000000000000000002",
          killedAt: 3000,
          claimed: false,
          killCount: 2,
        },
      ],
      [
        3,
        {
          addr: "0x3000000000000000000000000000000000000003",
          killedAt: 2000,
          claimed: false,
          killCount: 3,
        },
      ],
      [
        4,
        {
          addr: "0x4000000000000000000000000000000000000004",
          killedAt: 500,
          claimed: false,
          killCount: 1,
        },
      ],
      [
        5,
        {
          addr: "0x5000000000000000000000000000000000000005",
          killedAt: 0,
          claimed: false,
          killCount: 2,
        },
      ],
    ]);

    vi.resetModules();
    vi.doMock("../src/blockchain/client.js", () => ({
      getHttpProvider: () => ({
        getBlockNumber: async () => 123_456,
      }),
      getWsProvider: () => ({
        websocket: { readyState: 1 },
      }),
      getOperatorWallet: () => ({}),
      resetWsProvider: () => {},
    }));
    vi.doMock("../src/blockchain/contract.js", () => ({
      getWsContract: () => ({}),
      fetchGameConfig: async (gameId: number) => ({
        gameId,
        title: "Rebuild Ordering Test",
        entryFee: 0n,
        minPlayers: 2,
        maxPlayers: 10,
        registrationDeadline: 1_700_000_000,
        gameDate: 1_700_000_100,
        expiryDeadline: 1_700_007_300,
        createdAt: 1_699_999_900,
        creator: "0x00000000000000000000000000000000000000aa",
        centerLat: 37774900,
        centerLng: -122419400,
        meetingLat: 37774900,
        meetingLng: -122419400,
        bps1st: 4000,
        bps2nd: 2000,
        bps3rd: 1000,
        bpsKills: 1000,
        bpsCreator: 1000,
        baseReward: 0n,
        maxDuration: 7200,
      }),
      fetchGameState: async () => ({
        phase: 2,
        playerCount: 5,
        totalCollected: 0n,
        winner1: 5,
        winner2: 2,
        winner3: 3,
        topKiller: 1,
      }),
      fetchZoneShrinks: async () => [{ atSecond: 0, radiusMeters: 500 }],
      fetchNextGameId: async () => 2,
      fetchPlayer: async (_gameId: number, playerNumber: number) => {
        const player = chainPlayers.get(playerNumber);
        if (!player) throw new Error(`Missing player snapshot for ${playerNumber}`);
        return player;
      },
      resetWsContract: () => {},
    }));

    listener = await import("../src/blockchain/listener.js");
    queries = await import("../src/db/queries.js");
    queries.initDb();
  });

  afterEach(() => {
    vi.resetModules();
    rmSync(tempDir, { recursive: true, force: true });
  });

  it("keeps leaderboard order consistent after full DB rebuild", async () => {
    await listener.rebuildFromChain();

    const orderedPlayers = queries.getLeaderboard(1).map((entry) => entry.playerNumber);
    expect(orderedPlayers).toEqual([5, 2, 3, 1, 4]);
  });
});
