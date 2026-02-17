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

// Spectator rooms: gameId -> Set<WebSocket>
const spectatorRooms = new Map<number, Set<WebSocket>>();

// ws -> gameId (reverse lookup for spectators)
const spectatorMap = new WeakMap<WebSocket, number>();

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
 * Remove a player or spectator from their room.
 */
export function leaveRoom(ws: WebSocket): void {
  // Check if it's a player connection
  const conn = connectionMap.get(ws);
  if (conn) {
    const room = rooms.get(conn.gameId);
    if (room) {
      const current = room.get(conn.address);
      if (current && current.ws === ws) {
        room.delete(conn.address);
      }
      if (room.size === 0) {
        rooms.delete(conn.gameId);
      }
    }
    log.info({ gameId: conn.gameId, address: conn.address }, "Player left room");
    return;
  }

  // Check if it's a spectator connection
  leaveSpectator(ws);
}

/**
 * Add a spectator to a game room (no auth required).
 */
export function joinSpectator(gameId: number, ws: WebSocket): void {
  if (!spectatorRooms.has(gameId)) {
    spectatorRooms.set(gameId, new Set());
  }
  spectatorRooms.get(gameId)!.add(ws);
  spectatorMap.set(ws, gameId);
  log.info({ gameId, spectators: spectatorRooms.get(gameId)!.size }, "Spectator joined");
}

/**
 * Remove a spectator from their room.
 */
export function leaveSpectator(ws: WebSocket): void {
  const gameId = spectatorMap.get(ws);
  if (gameId === undefined) return;

  const room = spectatorRooms.get(gameId);
  if (room) {
    room.delete(ws);
    if (room.size === 0) {
      spectatorRooms.delete(gameId);
    }
  }
  log.info({ gameId }, "Spectator left");
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
 * Broadcast a message to all spectators in a game room.
 */
export function broadcastToSpectators(gameId: number, message: Record<string, unknown>): void {
  const room = spectatorRooms.get(gameId);
  if (!room) return;

  const payload = JSON.stringify(message);
  for (const ws of room) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(payload);
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

export function getRoomStats(): {
  playerGames: number;
  playerConnections: number;
  spectatorGames: number;
  spectatorConnections: number;
} {
  let playerConnections = 0;
  for (const room of rooms.values()) {
    playerConnections += room.size;
  }

  let spectatorConnections = 0;
  for (const room of spectatorRooms.values()) {
    spectatorConnections += room.size;
  }

  return {
    playerGames: rooms.size,
    playerConnections,
    spectatorGames: spectatorRooms.size,
    spectatorConnections,
  };
}
