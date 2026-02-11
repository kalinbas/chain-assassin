import { mkdirSync, writeFileSync } from "fs";
import { randomUUID } from "crypto";
import { extname } from "path";
import type { Request, Response } from "express";
import type { AuthenticatedRequest } from "./middleware.js";
import { getGameStatus, handleKillSubmission, handleCheckin, handleLocationUpdate, handleHeartbeatScan, checkAutoStart } from "../game/manager.js";
import { getOperatorWallet } from "../blockchain/client.js";
import { getPlayer, insertPhoto, getGamePhotos } from "../db/queries.js";
import { config } from "../config.js";
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

  const { lat, lng, qrPayload, bluetoothId } = req.body;
  if (lat == null || lng == null) {
    res.status(400).json({ error: "Missing required fields: lat, lng" });
    return;
  }

  const result = handleCheckin(gameId, authReq.playerAddress, lat, lng, qrPayload, bluetoothId);
  if (!result.success) {
    res.status(400).json({ error: result.error });
    return;
  }

  res.json({ success: true });
}

/**
 * POST /api/games/:gameId/heartbeat
 * Body: { qrPayload, lat, lng, bleNearbyAddresses? }
 */
export function submitHeartbeat(req: Request, res: Response): void {
  const authReq = req as AuthenticatedRequest;
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const { qrPayload, lat, lng, bleNearbyAddresses } = req.body;

  if (!qrPayload || lat == null || lng == null) {
    res.status(400).json({ error: "Missing required fields: qrPayload, lat, lng" });
    return;
  }

  const result = handleHeartbeatScan(
    gameId,
    authReq.playerAddress,
    qrPayload,
    lat,
    lng,
    bleNearbyAddresses || []
  );

  if (!result.success) {
    res.status(400).json({ error: result.error });
    return;
  }

  res.json({ success: true, scannedPlayerNumber: result.scannedPlayerNumber });
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

/**
 * POST /api/games/:gameId/photos
 * Multipart upload with walletAuth. Expects `photo` field (JPEG/PNG).
 */
export function uploadPhoto(req: Request, res: Response): void {
  const authReq = req as AuthenticatedRequest;
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const file = (req as unknown as { file?: Express.Multer.File }).file;
  if (!file) {
    res.status(400).json({ error: "No photo uploaded" });
    return;
  }

  // Validate mime type
  const allowed = ["image/jpeg", "image/png"];
  if (!allowed.includes(file.mimetype)) {
    res.status(400).json({ error: "Only JPEG and PNG files are allowed" });
    return;
  }

  // Verify player is registered in this game
  const player = getPlayer(gameId, authReq.playerAddress);
  if (!player) {
    res.status(403).json({ error: "Not a player in this game" });
    return;
  }

  // Save file
  const ext = extname(file.originalname) || (file.mimetype === "image/png" ? ".png" : ".jpg");
  const filename = `${randomUUID()}${ext}`;
  const gameDir = `${config.photosDir}/${gameId}`;
  mkdirSync(gameDir, { recursive: true });

  writeFileSync(`${gameDir}/${filename}`, file.buffer);

  const caption = typeof req.body.caption === "string" ? req.body.caption.slice(0, 200) : null;
  const now = Math.floor(Date.now() / 1000);
  const photoId = insertPhoto(gameId, authReq.playerAddress, filename, caption, now);

  log.info({ gameId, photoId, player: authReq.playerAddress }, "Photo uploaded");
  res.json({ success: true, photoId });
}

/**
 * GET /api/games/:gameId/photos
 * Public endpoint — returns photo list for a game.
 */
export function getPhotos(req: Request, res: Response): void {
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const photos = getGamePhotos(gameId);
  res.json(photos.map((p) => ({
    id: p.id,
    url: `/photos/${gameId}/${p.filename}`,
    caption: p.caption,
    timestamp: p.timestamp,
  })));
}
