import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

describe("game recovery", () => {
  let tempDir = "";
  let queries: typeof import("../src/db/queries.js");
  let manager: typeof import("../src/game/manager.js");
  let types: typeof import("../src/utils/types.js");

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));

    tempDir = mkdtempSync(join(tmpdir(), "chain-assassin-recovery-"));

    process.env.DB_PATH = join(tempDir, "test.db");
    process.env.RPC_URL = "http://127.0.0.1:8545";
    process.env.RPC_WS_URL = "ws://127.0.0.1:8545";
    process.env.CONTRACT_ADDRESS = "0x0000000000000000000000000000000000000001";
    process.env.OPERATOR_PRIVATE_KEY = `0x${"11".repeat(32)}`;
    process.env.PREGAME_DURATION_SECONDS = "10";

    vi.resetModules();
    queries = await import("../src/db/queries.js");
    manager = await import("../src/game/manager.js");
    types = await import("../src/utils/types.js");
    queries.initDb();
  });

  afterEach(() => {
    manager.removeSimulatedGame(1);
    manager.cleanupAll();
    vi.useRealTimers();
    rmSync(tempDir, { recursive: true, force: true });
  });

  function seedPregameActiveGame(subPhaseStartedAt: number): void {
    const nowSec = Math.floor(Date.now() / 1000);

    queries.insertGame({
      gameId: 1,
      title: "Recovery Test",
      entryFee: 0n,
      minPlayers: 2,
      maxPlayers: 10,
      registrationDeadline: nowSec - 3600,
      gameDate: nowSec - 1800,
      expiryDeadline: nowSec + 3600,
      createdAt: nowSec - 4000,
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
      phase: types.GamePhase.ACTIVE,
      playerCount: 2,
      totalCollected: "0",
    });

    queries.insertZoneShrinks(1, [{ atSecond: 0, radiusMeters: 500 }]);
    queries.insertPlayer(1, "0x0000000000000000000000000000000000000001", 1);
    queries.insertPlayer(1, "0x0000000000000000000000000000000000000002", 2);
    queries.setPlayerCheckedIn(1, "0x0000000000000000000000000000000000000001");
    queries.setPlayerCheckedIn(1, "0x0000000000000000000000000000000000000002");
    queries.updateGamePhase(1, types.GamePhase.ACTIVE, {
      startedAt: nowSec - 120,
      subPhase: "pregame",
      subPhaseStartedAt,
    });
  }

  function seedInGameActiveGame(nowSec: number): void {
    queries.insertGame({
      gameId: 1,
      title: "Recovery Test",
      entryFee: 0n,
      minPlayers: 3,
      maxPlayers: 10,
      registrationDeadline: nowSec - 3600,
      gameDate: nowSec - 1800,
      expiryDeadline: nowSec + 3600,
      createdAt: nowSec - 4000,
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
      phase: types.GamePhase.ACTIVE,
      playerCount: 3,
      totalCollected: "0",
    });

    queries.insertZoneShrinks(1, [{ atSecond: 0, radiusMeters: 500 }]);
    queries.insertPlayer(1, "0x0000000000000000000000000000000000000001", 1);
    queries.insertPlayer(1, "0x0000000000000000000000000000000000000002", 2);
    queries.insertPlayer(1, "0x0000000000000000000000000000000000000003", 3);
    queries.setTargetAssignment(1, "0x0000000000000000000000000000000000000001", "0x0000000000000000000000000000000000000002", nowSec - 300);
    queries.setTargetAssignment(1, "0x0000000000000000000000000000000000000002", "0x0000000000000000000000000000000000000003", nowSec - 300);
    queries.setTargetAssignment(1, "0x0000000000000000000000000000000000000003", "0x0000000000000000000000000000000000000001", nowSec - 300);

    // Player #1 has been outside the zone longer than grace, so recovery should
    // continue the countdown and eliminate immediately on first tick.
    queries.insertLocationPing({
      gameId: 1,
      address: "0x0000000000000000000000000000000000000001",
      lat: 0,
      lng: 0,
      timestamp: nowSec - 70,
      isInZone: false,
    });
    queries.insertLocationPing({
      gameId: 1,
      address: "0x0000000000000000000000000000000000000002",
      lat: 37.7749,
      lng: -122.4194,
      timestamp: nowSec - 5,
      isInZone: true,
    });
    queries.insertLocationPing({
      gameId: 1,
      address: "0x0000000000000000000000000000000000000003",
      lat: 37.7749,
      lng: -122.4194,
      timestamp: nowSec - 5,
      isInZone: true,
    });

    queries.updateGamePhase(1, types.GamePhase.ACTIVE, {
      startedAt: nowSec - 600,
      subPhase: "game",
      subPhaseStartedAt: nowSec - 540,
    });
  }

  it("resumes pregame using remaining countdown after restart", () => {
    const nowSec = Math.floor(Date.now() / 1000);
    const subPhaseStartedAt = nowSec - 8; // 2s remaining when duration=10s
    seedPregameActiveGame(subPhaseStartedAt);

    manager.recoverGames();

    const status = manager.getGameStatus(1);
    expect(status?.subPhase).toBe("pregame");
    expect(status?.pregameEndsAt).toBe(subPhaseStartedAt + 10);

    vi.advanceTimersByTime(1500);
    expect(queries.getGame(1)?.subPhase).toBe("pregame");

    vi.advanceTimersByTime(600);
    expect(queries.getGame(1)?.subPhase).toBe("game");
  });

  it("immediately advances overdue pregame to game on restart", () => {
    const nowSec = Math.floor(Date.now() / 1000);
    const subPhaseStartedAt = nowSec - 30; // already expired when duration=10s
    seedPregameActiveGame(subPhaseStartedAt);

    manager.recoverGames();

    expect(queries.getGame(1)?.subPhase).toBe("game");
  });

  it("rehydrates out-of-zone grace state for active game recovery", async () => {
    const nowSec = Math.floor(Date.now() / 1000);
    seedInGameActiveGame(nowSec);
    manager.addSimulatedGame(1);

    manager.recoverGames();
    await Promise.resolve();

    const playerOne = queries.getPlayer(1, "0x0000000000000000000000000000000000000001");
    expect(playerOne?.isAlive).toBe(false);
    expect(queries.getAlivePlayerCount(1)).toBe(2);
  });
});
