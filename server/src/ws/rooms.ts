import { WebSocket } from "ws";
import { createLogger } from "../utils/logger.js";

const log = createLogger("rooms");

interface PlayerConnection {
  ws: WebSocket;
  address: string;
  gameId: number;
}

// gameId -> Map<address, PlayerConnection>
const rooms = new Map<number, Map<string, PlayerConnection>>();

// ws -> PlayerConnection (reverse lookup)
const connectionMap = new WeakMap<WebSocket, PlayerConnection>();

/**
 * Add a player to a game room.
 */
export function joinRoom(gameId: number, address: string, ws: WebSocket): void {
  if (!rooms.has(gameId)) {
    rooms.set(gameId, new Map());
  }

  const room = rooms.get(gameId)!;

  // Close existing connection for this player (reconnect)
  const existing = room.get(address);
  if (existing && existing.ws !== ws && existing.ws.readyState === WebSocket.OPEN) {
    existing.ws.close(1000, "Reconnected from another session");
  }

  const conn: PlayerConnection = { ws, address, gameId };
  room.set(address, conn);
  connectionMap.set(ws, conn);

  log.info({ gameId, address, roomSize: room.size }, "Player joined room");
}

/**
 * Remove a player from their game room.
 */
export function leaveRoom(ws: WebSocket): void {
  const conn = connectionMap.get(ws);
  if (!conn) return;

  const room = rooms.get(conn.gameId);
  if (room) {
    // Only remove if this is still the active connection for this player
    const current = room.get(conn.address);
    if (current && current.ws === ws) {
      room.delete(conn.address);
    }

    // Cleanup empty rooms
    if (room.size === 0) {
      rooms.delete(conn.gameId);
    }
  }

  log.info({ gameId: conn.gameId, address: conn.address }, "Player left room");
}

/**
 * Broadcast a message to all players in a game room.
 */
export function broadcastToRoom(gameId: number, message: Record<string, unknown>): void {
  const room = rooms.get(gameId);
  if (!room) return;

  const payload = JSON.stringify(message);
  for (const [, conn] of room) {
    if (conn.ws.readyState === WebSocket.OPEN) {
      conn.ws.send(payload);
    }
  }
}

/**
 * Send a message to a specific player in a game.
 */
export function sendToPlayer(
  gameId: number,
  address: string,
  message: Record<string, unknown>
): void {
  const room = rooms.get(gameId);
  if (!room) return;

  const conn = room.get(address.toLowerCase());
  if (conn && conn.ws.readyState === WebSocket.OPEN) {
    conn.ws.send(JSON.stringify(message));
  }
}

/**
 * Get the connection info for a WebSocket.
 */
export function getConnection(ws: WebSocket): PlayerConnection | undefined {
  return connectionMap.get(ws);
}

/**
 * Get the number of connected players in a room.
 */
export function getRoomSize(gameId: number): number {
  return rooms.get(gameId)?.size ?? 0;
}
