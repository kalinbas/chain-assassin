import { WebSocketServer, WebSocket } from "ws";
import type { Server as HttpServer } from "http";
import { handleWsMessage } from "./handlers.js";
import { leaveRoom, broadcastToRoom, broadcastToSpectators, sendToPlayer } from "./rooms.js";
import { setBroadcast, setSpectatorBroadcast } from "../game/manager.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("ws");

let wss: WebSocketServer;

/**
 * Initialize the WebSocket server on the given HTTP server.
 */
export function initWebSocketServer(server: HttpServer): WebSocketServer {
  wss = new WebSocketServer({ server, path: "/ws" });

  wss.on("connection", (ws: WebSocket) => {
    log.debug("New WebSocket connection");

    ws.on("message", (data) => {
      try {
        handleWsMessage(ws, data.toString());
      } catch (err) {
        log.error({ error: (err as Error).message }, "Error handling WS message");
      }
    });

    ws.on("close", () => {
      leaveRoom(ws);
      log.debug("WebSocket connection closed");
    });

    ws.on("error", (err) => {
      log.error({ error: err.message }, "WebSocket error");
      leaveRoom(ws);
    });

    // Send heartbeat ping every 30s
    const pingInterval = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.ping();
      } else {
        clearInterval(pingInterval);
      }
    }, 30_000);

    ws.on("close", () => clearInterval(pingInterval));
  });

  // Wire up broadcast functions to game manager
  setBroadcast(broadcastToRoom, sendToPlayer);
  setSpectatorBroadcast(broadcastToSpectators);

  log.info("WebSocket server initialized on /ws");
  return wss;
}

