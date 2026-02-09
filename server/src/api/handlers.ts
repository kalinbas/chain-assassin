import type { Request, Response } from "express";
import type { AuthenticatedRequest } from "./middleware.js";
import { getGameStatus, handleKillSubmission, handleCheckin, handleLocationUpdate, checkAutoStart } from "../game/manager.js";
import { getOperatorWallet } from "../blockchain/client.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("api");

/**
 * GET /health
 */
export function healthCheck(_req: Request, res: Response): void {
  res.json({ status: "ok", timestamp: Date.now() });
}

/**
 * GET /api/games/:gameId/status
 */
export function gameStatus(req: Request, res: Response): void {
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const status = getGameStatus(gameId);
  if (!status) {
    res.status(404).json({ error: "Game not found" });
    return;
  }

  res.json(status);
}

/**
 * POST /api/games/:gameId/kill
 * Body: { qrPayload, hunterLat, hunterLng, bleNearbyAddresses }
 */
export async function submitKill(req: Request, res: Response): Promise<void> {
  const authReq = req as AuthenticatedRequest;
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const { qrPayload, hunterLat, hunterLng, bleNearbyAddresses } = req.body;

  if (!qrPayload || hunterLat == null || hunterLng == null) {
    res.status(400).json({ error: "Missing required fields: qrPayload, hunterLat, hunterLng" });
    return;
  }

  try {
    const result = await handleKillSubmission(
      gameId,
      authReq.playerAddress,
      qrPayload,
      hunterLat,
      hunterLng,
      bleNearbyAddresses || []
    );

    if (!result.success) {
      res.status(400).json({ error: result.error });
      return;
    }

    res.json({ success: true });
  } catch (err) {
    log.error({ gameId, error: (err as Error).message }, "Kill submission error");
    res.status(500).json({ error: "Internal server error" });
  }
}

/**
 * POST /api/games/:gameId/location
 * Body: { lat, lng, timestamp }
 */
export function submitLocation(req: Request, res: Response): void {
  const authReq = req as AuthenticatedRequest;
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const { lat, lng } = req.body;
  if (lat == null || lng == null) {
    res.status(400).json({ error: "Missing required fields: lat, lng" });
    return;
  }

  handleLocationUpdate(gameId, authReq.playerAddress, lat, lng);
  res.json({ success: true });
}

/**
 * POST /api/games/:gameId/checkin
 * Body: { lat, lng, qrPayload? }
 *
 * First player checks in via GPS only. All subsequent players must also
 * provide the QR code of an already-checked-in player (viral check-in).
 */
export function submitCheckin(req: Request, res: Response): void {
  const authReq = req as AuthenticatedRequest;
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const { lat, lng, qrPayload } = req.body;
  if (lat == null || lng == null) {
    res.status(400).json({ error: "Missing required fields: lat, lng" });
    return;
  }

  const result = handleCheckin(gameId, authReq.playerAddress, lat, lng, qrPayload);
  if (!result.success) {
    res.status(400).json({ error: result.error });
    return;
  }

  res.json({ success: true });
}

/**
 * POST /api/admin/check-auto-start
 * Operator-only: trigger auto-start check on demand.
 */
export async function triggerAutoStart(req: Request, res: Response): Promise<void> {
  const authReq = req as AuthenticatedRequest;
  const operatorAddress = getOperatorWallet().address.toLowerCase();

  if (authReq.playerAddress !== operatorAddress) {
    res.status(403).json({ error: "Operator only" });
    return;
  }

  try {
    await checkAutoStart();
    res.json({ success: true });
  } catch (err) {
    log.error({ error: (err as Error).message }, "Admin auto-start check failed");
    res.status(500).json({ error: "Internal server error" });
  }
}
