import { config } from "../config.js";
import { haversineDistance } from "../utils/geo.js";
import { parseKillQrPayload } from "../utils/crypto.js";
import { hasBleMatch, normalizeBluetoothId } from "./ble.js";
import {
  getPlayer,
  getPlayerByNumber,
  getTargetAssignment,
  getLatestLocationPing,
} from "../db/queries.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("killVerifier");

export interface KillVerificationResult {
  valid: boolean;
  error?: string;
  targetAddress?: string;
  distanceMeters?: number;
  targetLat?: number;
  targetLng?: number;
}

/**
 * Verify a kill submission.
 *
 * Steps:
 * 1. Parse QR payload to extract game ID + player number
 * 2. Resolve player number to wallet address
 * 3. Verify target is the hunter's assigned target
 * 4. Check GPS proximity (hunter → target last known position)
 * 5. Check BLE proximity (if required)
 */
export function verifyKill(
  gameId: number,
  hunterAddress: string,
  qrPayload: string,
  hunterLat: number,
  hunterLng: number,
  bleNearbyAddresses: string[]
): KillVerificationResult {
  // 1. Parse obfuscated numeric QR payload (server/client shared codec)
  const qr = parseKillQrPayload(qrPayload);
  if (!qr) {
    return { valid: false, error: "Invalid QR code format" };
  }

  if (qr.gameId !== gameId) {
    return { valid: false, error: "QR code is for a different game" };
  }

  // 2. Resolve player number to address
  const targetPlayer = getPlayerByNumber(gameId, qr.playerNumber);
  if (!targetPlayer) {
    return { valid: false, error: "Player not found for QR code" };
  }
  const targetAddress = targetPlayer.address;

  // 3. Verify hunter is alive and registered
  const hunter = getPlayer(gameId, hunterAddress);
  if (!hunter) {
    return { valid: false, error: "Hunter not registered" };
  }
  if (!hunter.isAlive) {
    return { valid: false, error: "Hunter is eliminated" };
  }

  // 4. Verify target is alive
  if (!targetPlayer.isAlive) {
    return { valid: false, error: "Target is already eliminated" };
  }

  // 5. Verify target is hunter's assigned target
  const assignment = getTargetAssignment(gameId, hunterAddress);
  if (!assignment || assignment.targetAddress !== targetAddress) {
    return { valid: false, error: "This player is not your assigned target" };
  }

  // 6. Check GPS proximity
  const targetPing = getLatestLocationPing(gameId, targetAddress);
  let distanceMeters: number | undefined;
  let targetLat: number | undefined;
  let targetLng: number | undefined;

  if (targetPing) {
    distanceMeters = haversineDistance(
      hunterLat,
      hunterLng,
      targetPing.lat,
      targetPing.lng
    );
    targetLat = targetPing.lat;
    targetLng = targetPing.lng;

    if (distanceMeters > config.killProximityMeters) {
      return {
        valid: false,
        error: `Too far from target (${Math.round(distanceMeters)}m, max ${config.killProximityMeters}m)`,
        targetAddress,
        distanceMeters,
        targetLat,
        targetLng,
      };
    }
  } else {
    log.warn(
      { gameId, target: targetAddress },
      "No location ping for target, skipping GPS check"
    );
  }

  // 7. Check BLE proximity (if required)
  if (config.bleRequired) {
    const targetBluetoothId = normalizeBluetoothId(targetPlayer.bluetoothId);

    // Auto-seeded/legacy players may not have a bluetooth_id yet.
    // In that case we cannot enforce BLE reliably for this target.
    if (!targetBluetoothId) {
      log.warn(
        { gameId, target: targetAddress },
        "Target has no bluetooth_id; skipping BLE check"
      );
    } else if (!hasBleMatch(targetBluetoothId, bleNearbyAddresses)) {
      return {
        valid: false,
        error: "Target not detected via Bluetooth",
        targetAddress,
        distanceMeters,
        targetLat,
        targetLng,
      };
    }
  }

  log.info(
    { gameId, hunter: hunterAddress, target: targetAddress, distanceMeters },
    "Kill verified"
  );

  return {
    valid: true,
    targetAddress,
    distanceMeters,
    targetLat,
    targetLng,
  };
}
