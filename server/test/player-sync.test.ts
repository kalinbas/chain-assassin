import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

describe("player sync safeguards", () => {
  let tempDir = "";
  let queries: typeof import("../src/db/queries.js");

  beforeEach(async () => {
    tempDir = mkdtempSync(join(tmpdir(), "chain-assassin-player-sync-"));

    process.env.DB_PATH = join(tempDir, "test.db");
    process.env.RPC_URL = "http://127.0.0.1:8545";
    process.env.RPC_WS_URL = "ws://127.0.0.1:8545";
    process.env.CONTRACT_ADDRESS = "0x0000000000000000000000000000000000000001";
    process.env.OPERATOR_PRIVATE_KEY = `0x${"11".repeat(32)}`;

    vi.resetModules();
    queries = await import("../src/db/queries.js");
    queries.initDb();

    const nowSec = Math.floor(Date.now() / 1000);
    queries.insertGame({
      gameId: 1,
      title: "Player Sync Test",
      entryFee: 0n,
      minPlayers: 2,
      maxPlayers: 10,
      registrationDeadline: nowSec + 3600,
      gameDate: nowSec + 7200,
      expiryDeadline: nowSec + 10800,
      createdAt: nowSec,
      creator: "0x00000000000000000000000000000000000000aa",
      centerLat: 19435700,
      centerLng: -99129900,
      meetingLat: 19435700,
      meetingLng: -99129900,
      bps1st: 3500,
      bps2nd: 1500,
      bps3rd: 1000,
      bpsKills: 2000,
      bpsCreator: 1000,
      baseReward: 0n,
      maxDuration: 7200,
      phase: 0,
      playerCount: 0,
      totalCollected: "0",
    });
  });

  afterEach(() => {
    rmSync(tempDir, { recursive: true, force: true });
  });

  it("ignores zero-address registrations", () => {
    queries.insertPlayer(1, "0x0000000000000000000000000000000000000000", 1);
    expect(queries.getPlayerCount(1)).toBe(0);
    expect(queries.getPlayerByNumber(1, 1)).toBeNull();
  });

  it("replaces legacy zero-address placeholder with canonical player address", () => {
    const db = queries.getDb();
    db.prepare("INSERT INTO players (game_id, address, player_number, is_alive) VALUES (?, ?, ?, ?)")
      .run(1, "0x0000000000000000000000000000000000000000", 4, 1);

    const canonical = "0xc33ccc437c56cfcfa089aa708629a384a7ea813c";
    queries.insertPlayer(1, canonical, 4);

    const player = queries.getPlayerByNumber(1, 4);
    expect(player?.address).toBe(canonical);
    expect(queries.getPlayerCount(1)).toBe(1);
  });

  it("excludes zero-address ghost rows from alive and leaderboard views", () => {
    const db = queries.getDb();
    db.prepare("INSERT INTO players (game_id, address, player_number, is_alive) VALUES (?, ?, ?, ?)")
      .run(1, "0x0000000000000000000000000000000000000000", 2, 1);
    queries.insertPlayer(1, "0x5e2b68de8e390b9afec264a063d8c3f9ff2b85a9", 1);

    expect(queries.getAlivePlayerCount(1)).toBe(1);
    expect(queries.getPlayers(1)).toHaveLength(1);
    expect(queries.getLeaderboard(1)).toHaveLength(1);
  });

  it("persists on-chain snapshot fields during player upsert", () => {
    const addr = "0x56a87f8f8eb6b2d4b4d10fd4bf5e6a2b1a0b34cd";
    queries.insertPlayer(1, addr, 7, {
      isAlive: false,
      kills: 3,
      hasClaimed: true,
    });

    const player = queries.getPlayerByNumber(1, 7);
    expect(player).not.toBeNull();
    expect(player?.isAlive).toBe(false);
    expect(player?.kills).toBe(3);
    expect(player?.hasClaimed).toBe(true);

    const leaderboard = queries.getLeaderboard(1);
    expect(leaderboard).toHaveLength(1);
    expect(leaderboard[0].playerNumber).toBe(7);
    expect(leaderboard[0].kills).toBe(3);
    expect(leaderboard[0].isAlive).toBe(false);
  });

  it("does not refresh heartbeat timestamps for eliminated players", () => {
    const addr = "0x9d9e9f9000000000000000000000000000000001";
    queries.insertPlayer(1, addr, 9, {
      isAlive: false,
      lastHeartbeatAt: 100,
    });

    const updated = queries.updateLastHeartbeat(1, addr, 200);
    expect(updated).toBe(false);
    expect(queries.getPlayer(1, addr)?.lastHeartbeatAt).toBe(100);
  });

  it("orders leaderboard the same way as winner logic", () => {
    queries.insertPlayer(1, "0x1000000000000000000000000000000000000001", 1, {
      isAlive: false,
      kills: 3,
      eliminatedAt: 1000,
    });
    queries.insertPlayer(1, "0x2000000000000000000000000000000000000002", 2, {
      isAlive: false,
      kills: 2,
      eliminatedAt: 3000,
    });
    queries.insertPlayer(1, "0x3000000000000000000000000000000000000003", 3, {
      isAlive: false,
      kills: 3,
      eliminatedAt: 2000,
    });
    queries.insertPlayer(1, "0x4000000000000000000000000000000000000004", 4, {
      isAlive: false,
      kills: 1,
      eliminatedAt: 500,
    });
    queries.insertPlayer(1, "0x5000000000000000000000000000000000000005", 5, {
      isAlive: true,
      kills: 2,
    });

    const ordered = queries.getLeaderboard(1).map((entry) => entry.playerNumber);
    expect(ordered).toEqual([5, 2, 3, 1, 4]);
  });
});
