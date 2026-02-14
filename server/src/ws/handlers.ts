import { WebSocket } from "ws";
import { verifySignature, validateAuthMessage } from "../utils/crypto.js";
import { getPlayer, getGame, getTargetAssignment, findHunterOf, getAlivePlayers, getAlivePlayerCount, getLatestLocationPing } from "../db/queries.js";
import { handleLocationUpdate, handleHeartbeatScan, getGameStatus } from "../game/manager.js";
import { joinRoom, joinSpectator, getConnection } from "./rooms.js";
import { createLogger } from "../utils/logger.js";
import type { WsClientMessage } from "../utils/types.js";
import { config } from "../config.js";

const log = createLogger("wsHandlers");

/**
 * Handle an incoming WebSocket message.
 */
export function handleWsMessage(ws: WebSocket, raw: string): void {
  let message: WsClientMessage;
  try {
    message = JSON.parse(raw);
  } catch {
    sendError(ws, "Invalid JSON");
    return;
  }

  switch (message.type) {
    case "auth":
      handleAuth(ws, message);
      break;

    case "location":
      handleLocation(ws, message);
      break;

    case "heartbeat_scan":
      handleWsHeartbeatScan(ws, message as { type: "heartbeat_scan"; qrPayload: string; lat: number; lng: number; bleNearbyAddresses?: string[] });
      break;

    case "spectate":
      handleSpectate(ws, message as { type: "spectate"; gameId: number });
      break;

    default:
      sendError(ws, `Unknown message type: ${(message as { type: string }).type}`);
  }
}

/**
 * Handle WebSocket authentication.
 */
function handleAuth(
  ws: WebSocket,
  msg: { type: "auth"; gameId: number; address: string; signature: string; message: string }
): void {
  const { gameId, address, signature, message } = msg;

  const validation = validateAuthMessage(message, gameId);
  if (!validation.valid) {
    sendError(ws, `Auth failed: ${validation.error}`);
    return;
  }

  if (!verifySignature(message, signature, address)) {
    sendError(ws, "Auth failed: invalid signature");
    return;
  }

  const player = getPlayer(gameId, address);
  if (!player) {
    sendError(ws, "Auth failed: not registered for this game");
    return;
  }

  joinRoom(gameId, address.toLowerCase(), ws);

  const targetAssignment = getTargetAssignment(gameId, address.toLowerCase());
  const hunterAddress = findHunterOf(gameId, address.toLowerCase());
  const hunterPlayer = hunterAddress ? getPlayer(gameId, hunterAddress) : null;
  const game = getGame(gameId);
  const aliveCount = getAlivePlayerCount(gameId);
  const heartbeatDeadline = player.lastHeartbeatAt != null
    ? player.lastHeartbeatAt + config.heartbeatIntervalSeconds
    : null;
  const heartbeatDisabled =
    game?.subPhase === "game" ? aliveCount <= config.heartbeatDisableThreshold : false;
  const pregameEndsAt = game?.subPhase === "pregame" && game.subPhaseStartedAt != null
    ? game.subPhaseStartedAt + config.pregameDurationSeconds
    : null;

  ws.send(
    JSON.stringify({
      type: "auth:success",
      gameId,
      address: address.toLowerCase(),
      playerNumber: player.playerNumber,
      isAlive: player.isAlive,
      kills: player.kills,
      target: targetAssignment
        ? {
            address: targetAssignment.targetAddress,
            playerNumber:
              getPlayer(gameId, targetAssignment.targetAddress)?.playerNumber ?? 0,
          }
        : null,
      hunterPlayerNumber: hunterPlayer?.playerNumber ?? null,
      lastHeartbeatAt: player.lastHeartbeatAt,
      heartbeatDeadline,
      heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
      heartbeatDisableThreshold: config.heartbeatDisableThreshold,
      heartbeatDisabled,
      subPhase: game?.subPhase ?? null,
      checkinEndsAt: game?.subPhase === "checkin"
        ? game.expiryDeadline
        : null,
      pregameEndsAt,
    })
  );

  log.info({ gameId, address }, "Player authenticated via WebSocket");
}

/**
 * Handle location update from player.
 */
function handleLocation(
  ws: WebSocket,
  msg: { type: "location"; lat: number; lng: number; timestamp: number }
): void {
  const conn = getConnection(ws);
  if (!conn) {
    sendError(ws, "Not authenticated");
    return;
  }

  const result = handleLocationUpdate(conn.gameId, conn.address, msg.lat, msg.lng);
  if (!result.success) {
    sendError(ws, result.error ?? "Location update failed");
  }
}

/**
 * Handle spectator join (no auth required).
 */
function handleSpectate(
  ws: WebSocket,
  msg: { type: "spectate"; gameId: number }
): void {
  const { gameId } = msg;

  const status = getGameStatus(gameId);
  if (!status) {
    sendError(ws, "Game not found");
    return;
  }

  joinSpectator(gameId, ws);

  const alivePlayers = getAlivePlayers(gameId);
  const players = alivePlayers.map((p) => {
    const ping = getLatestLocationPing(gameId, p.address);
    return {
      address: p.address,
      playerNumber: p.playerNumber,
      lat: ping?.lat ?? null,
      lng: ping?.lng ?? null,
      isAlive: p.isAlive,
      kills: p.kills,
    };
  });

  ws.send(
    JSON.stringify({
      type: "spectate:init",
      gameId,
      phase: status.phase,
      subPhase: status.subPhase,
      checkinEndsAt: status.checkinEndsAt,
      pregameEndsAt: status.pregameEndsAt,
      playerCount: status.playerCount,
      aliveCount: status.aliveCount,
      checkedInCount: status.checkedInCount,
      leaderboard: status.leaderboard,
      zone: status.zone,
      players,
      winner1: status.winner1,
      winner2: status.winner2,
      winner3: status.winner3,
      topKiller: status.topKiller,
    })
  );

  log.info({ gameId }, "Spectator connected");
}

/**
 * Handle heartbeat scan via WebSocket.
 */
function handleWsHeartbeatScan(
  ws: WebSocket,
  msg: { type: "heartbeat_scan"; qrPayload: string; lat: number; lng: number; bleNearbyAddresses?: string[] }
): void {
  const conn = getConnection(ws);
  if (!conn) {
    sendError(ws, "Not authenticated");
    return;
  }

  const result = handleHeartbeatScan(
    conn.gameId,
    conn.address,
    msg.qrPayload,
    msg.lat,
    msg.lng,
    msg.bleNearbyAddresses || []
  );

  if (!result.success) {
    ws.send(JSON.stringify({ type: "heartbeat:error", error: result.error }));
    return;
  }

  // Success response is sent via sendToPlayer inside handleHeartbeatScan
}

function sendError(ws: WebSocket, message: string): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: "error", message }));
  }
}
