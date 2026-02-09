import { WebSocket } from "ws";
import { verifySignature, validateAuthMessage } from "../utils/crypto.js";
import { getPlayer, getTargetAssignment } from "../db/queries.js";
import { handleLocationUpdate } from "../game/manager.js";
import { joinRoom, getConnection } from "./rooms.js";
import { createLogger } from "../utils/logger.js";
import type { WsClientMessage } from "../utils/types.js";

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

    case "ble_proximity":
      // BLE data is stored in-memory temporarily — used during kill verification
      // For now just acknowledge receipt
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

  // Validate message format and freshness
  const validation = validateAuthMessage(message, gameId);
  if (!validation.valid) {
    sendError(ws, `Auth failed: ${validation.error}`);
    return;
  }

  // Verify signature
  if (!verifySignature(message, signature, address)) {
    sendError(ws, "Auth failed: invalid signature");
    return;
  }

  // Verify player is registered for this game
  const player = getPlayer(gameId, address);
  if (!player) {
    sendError(ws, "Auth failed: not registered for this game");
    return;
  }

  // Join the game room
  joinRoom(gameId, address.toLowerCase(), ws);

  // Send auth success with current game state
  const targetAssignment = getTargetAssignment(gameId, address.toLowerCase());

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

  handleLocationUpdate(conn.gameId, conn.address, msg.lat, msg.lng);
}

function sendError(ws: WebSocket, message: string): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: "error", message }));
  }
}
