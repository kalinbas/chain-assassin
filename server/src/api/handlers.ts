import { mkdirSync, writeFileSync } from "fs";
import { randomUUID } from "crypto";
import { extname } from "path";
import type { Request, Response } from "express";
import type { AuthenticatedRequest } from "./middleware.js";
import { getGameStatus, handleKillSubmission, handleCheckin, handleLocationUpdate, handleHeartbeatScan, checkAllRegistrationGames } from "../game/manager.js";
import { getOperatorWallet } from "../blockchain/client.js";
import { getPlayer, insertPhoto, getGamePhotos, getAllGames, getGame, getZoneShrinks, getGameActivity, getPlayers } from "../db/queries.js";
import { getLeaderboard } from "../game/leaderboard.js";
import { config } from "../config.js";
import { createLogger } from "../utils/logger.js";
import { contractCoordToDegrees } from "../utils/geo.js";

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

  const result = handleLocationUpdate(gameId, authReq.playerAddress, lat, lng);
  if (!result.success) {
    res.status(400).json({ error: result.error });
    return;
  }

  res.json({ success: true });
}

/**
 * POST /api/games/:gameId/checkin
 * Body: { lat, lng, qrPayload?, bluetoothId?, bleNearbyAddresses? }
 *
 * Players check in by scanning a checked-in player's QR code.
 * Initial seed players are checked in automatically by the server.
 */
export function submitCheckin(req: Request, res: Response): void {
  const authReq = req as AuthenticatedRequest;
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const { lat, lng, qrPayload, bluetoothId, bleNearbyAddresses } = req.body;
  if (lat == null || lng == null) {
    res.status(400).json({ error: "Missing required fields: lat, lng" });
    return;
  }

  const result = handleCheckin(
    gameId,
    authReq.playerAddress,
    lat,
    lng,
    qrPayload,
    bluetoothId,
    bleNearbyAddresses || []
  );
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
  const latNum = Number(lat);
  const lngNum = Number(lng);

  if (!qrPayload || lat == null || lng == null) {
    res.status(400).json({ error: "Missing required fields: qrPayload, lat, lng" });
    return;
  }
  if (!Number.isFinite(latNum) || latNum < -90 || latNum > 90) {
    res.status(400).json({ error: "Invalid latitude" });
    return;
  }
  if (!Number.isFinite(lngNum) || lngNum < -180 || lngNum > 180) {
    res.status(400).json({ error: "Invalid longitude" });
    return;
  }

  const result = handleHeartbeatScan(
    gameId,
    authReq.playerAddress,
    qrPayload,
    latNum,
    lngNum,
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
    await checkAllRegistrationGames();
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

// ============ Helper: format a game row for API response ============

function resolveWinnerNumber(gameId: number, address: string | null): number {
  if (!address) return 0;
  const p = getPlayer(gameId, address);
  return p?.playerNumber ?? 0;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function formatGameResponse(game: NonNullable<ReturnType<typeof getGame>>): Record<string, unknown> {
  const shrinks = getZoneShrinks(game.gameId);
  return {
    gameId: game.gameId,
    title: game.title,
    entryFee: game.entryFee.toString(),
    baseReward: game.baseReward.toString(),
    minPlayers: game.minPlayers,
    maxPlayers: game.maxPlayers,
    registrationDeadline: game.registrationDeadline,
    gameDate: game.gameDate,
    expiryDeadline: game.expiryDeadline,
    maxDuration: game.maxDuration,
    createdAt: game.createdAt,
    creator: game.creator,
    centerLat: game.centerLat,
    centerLng: game.centerLng,
    meetingLat: game.meetingLat,
    meetingLng: game.meetingLng,
    bps1st: game.bps1st,
    bps2nd: game.bps2nd,
    bps3rd: game.bps3rd,
    bpsKills: game.bpsKills,
    bpsCreator: game.bpsCreator,
    totalCollected: game.totalCollected,
    playerCount: game.playerCount,
    phase: game.phase,
    subPhase: game.subPhase,
    winner1: resolveWinnerNumber(game.gameId, game.winner1),
    winner2: resolveWinnerNumber(game.gameId, game.winner2),
    winner3: resolveWinnerNumber(game.gameId, game.winner3),
    topKiller: resolveWinnerNumber(game.gameId, game.topKiller),
    zoneShrinks: shrinks.map((s) => ({ atSecond: s.atSecond, radiusMeters: s.radiusMeters })),
  };
}

/**
 * GET /api/games
 * Public endpoint — returns all games.
 */
export function listGames(_req: Request, res: Response): void {
  const games = getAllGames();
  res.json(games.map((g) => formatGameResponse(g)));
}

/**
 * GET /api/games/:gameId
 * Public endpoint — returns full game detail including activity, leaderboard, zone.
 */
export function gameDetail(req: Request, res: Response): void {
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const game = getGame(gameId);
  if (!game) {
    res.status(404).json({ error: "Game not found" });
    return;
  }

  const base = formatGameResponse(game);
  const activity = getGameActivity(gameId);
  const leaderboard = getLeaderboard(gameId);
  const players = getPlayers(gameId);
  const aliveCount = players.filter((p) => p.isAlive).length;
  const checkedInCount = players.filter((p) => p.checkedIn).length;

  const checkinEndsAt = game.subPhase === "checkin" ? game.expiryDeadline : null;
  const pregameEndsAt = game.subPhase === "pregame" && game.subPhaseStartedAt != null
    ? game.subPhaseStartedAt + config.pregameDurationSeconds
    : null;

  // Zone state: use initial radius from shrinks if game is active
  let zone = null;
  if (game.phase === 1 && game.centerLat && game.centerLng) {
    const shrinks = getZoneShrinks(gameId);
    const initialRadius = shrinks[0]?.radiusMeters ?? 500;
    zone = {
      centerLat: contractCoordToDegrees(game.centerLat),
      centerLng: contractCoordToDegrees(game.centerLng),
      currentRadiusMeters: initialRadius,
      nextShrinkAt: null,
      nextRadiusMeters: null,
    };
  }

  // Try to get live zone from getGameStatus (has in-memory active game data)
  const liveStatus = getGameStatus(gameId);
  if (liveStatus?.zone) {
    zone = liveStatus.zone;
  }

  res.json({
    ...base,
    activity,
    leaderboard,
    aliveCount,
    checkedInCount,
    checkinEndsAt,
    pregameEndsAt,
    zone,
  });
}

/**
 * GET /api/games/:gameId/player/:address
 * Public endpoint — returns player info for a specific address.
 */
export function playerInfo(req: Request, res: Response): void {
  const gameId = parseInt(req.params.gameId, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "Invalid game ID" });
    return;
  }

  const address = req.params.address;
  if (!address) {
    res.status(400).json({ error: "Address required" });
    return;
  }

  const player = getPlayer(gameId, address);
  if (!player) {
    res.json({
      registered: false,
      alive: false,
      kills: 0,
      claimed: false,
      playerNumber: 0,
      checkedIn: false,
    });
    return;
  }

  res.json({
    registered: true,
    alive: player.isAlive,
    kills: player.kills,
    claimed: player.hasClaimed,
    playerNumber: player.playerNumber,
    checkedIn: player.checkedIn,
  });
}
