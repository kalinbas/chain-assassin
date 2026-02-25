import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

describe("kill verifier hard requirements", () => {
  let tempDir = "";
  let queries: typeof import("../src/db/queries.js");
  let verifyKill: typeof import("../src/game/killVerifier.js")["verifyKill"];
  let encodeKillQrPayload: typeof import("../src/utils/crypto.js")["encodeKillQrPayload"];

  const gameId = 1;
  const hunterAddress = "0x1000000000000000000000000000000000000001";
  const targetAddress = "0x2000000000000000000000000000000000000002";
  const nowSec = Math.floor(Date.now() / 1000);

  beforeEach(async () => {
    tempDir = mkdtempSync(join(tmpdir(), "chain-assassin-kill-verifier-"));

    process.env.DB_PATH = join(tempDir, "test.db");
    process.env.RPC_URL = "http://127.0.0.1:8545";
    process.env.RPC_WS_URL = "ws://127.0.0.1:8545";
    process.env.CONTRACT_ADDRESS = "0x0000000000000000000000000000000000000001";
    process.env.OPERATOR_PRIVATE_KEY = `0x${"11".repeat(32)}`;
    process.env.BLE_REQUIRED = "true";

    vi.resetModules();
    queries = await import("../src/db/queries.js");
    ({ verifyKill } = await import("../src/game/killVerifier.js"));
    ({ encodeKillQrPayload } = await import("../src/utils/crypto.js"));

    queries.initDb();
    queries.insertGame({
      gameId,
      title: "Kill Verifier Test",
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
      phase: 1,
      playerCount: 2,
      totalCollected: "0",
    });
    queries.insertPlayer(gameId, hunterAddress, 1, { checkedIn: true, bluetoothId: "71173941" });
    queries.insertPlayer(gameId, targetAddress, 2, { checkedIn: true, bluetoothId: "72" });
    queries.setTargetAssignment(gameId, hunterAddress, targetAddress, nowSec);
  });

  afterEach(() => {
    rmSync(tempDir, { recursive: true, force: true });
  });

  it("rejects kill when target location proof is missing", () => {
    const qrPayload = encodeKillQrPayload(gameId, 2);

    const result = verifyKill(
      gameId,
      hunterAddress,
      qrPayload,
      37.7749,
      -122.4194,
      ["72"]
    );

    expect(result.valid).toBe(false);
    expect(result.error).toBe("Target location is unavailable");
  });

  it("rejects kill when target bluetooth token is missing", () => {
    queries.insertPlayer(gameId, targetAddress, 2, { checkedIn: true, bluetoothId: null });
    queries.insertLocationPing({
      gameId,
      address: targetAddress,
      lat: 37.7749,
      lng: -122.4194,
      timestamp: nowSec,
      isInZone: true,
    });

    const qrPayload = encodeKillQrPayload(gameId, 2);
    const result = verifyKill(
      gameId,
      hunterAddress,
      qrPayload,
      37.77491,
      -122.41941,
      ["72"]
    );

    expect(result.valid).toBe(false);
    expect(result.error).toBe("Target Bluetooth token is unavailable");
  });

  it("accepts kill when GPS and BLE proofs are present", () => {
    queries.insertLocationPing({
      gameId,
      address: targetAddress,
      lat: 37.7749,
      lng: -122.4194,
      timestamp: nowSec,
      isInZone: true,
    });

    const qrPayload = encodeKillQrPayload(gameId, 2);
    const result = verifyKill(
      gameId,
      hunterAddress,
      qrPayload,
      37.77491,
      -122.41941,
      ["72"]
    );

    expect(result.valid).toBe(true);
    expect(result.targetAddress).toBe(targetAddress);
  });
});
