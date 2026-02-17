import { createServer } from "http";
import { mkdirSync } from "fs";
import express from "express";
import cors from "cors";
import { config } from "./config.js";
import { createLogger } from "./utils/logger.js";
import { initDb } from "./db/queries.js";
import { initWebSocketServer } from "./ws/server.js";
import { router } from "./api/routes.js";
import { startEventListener, backfillEvents, rebuildFromChain, stopEventListener } from "./blockchain/listener.js";
import { recoverGames, cleanupAll } from "./game/manager.js";
import { recoverSimulation } from "./simulation/simulator.js";
import { closeProviders } from "./blockchain/client.js";

const log = createLogger("main");

async function main(): Promise<void> {
  log.info("Chain Assassin Game Server starting...");

  // 1. Initialize database
  initDb();

  // 2. Set up Express app
  const app = express();
  app.use(cors());
  app.use(express.json());

  // Serve uploaded photos
  mkdirSync(config.photosDir, { recursive: true });
  app.use("/photos", express.static(config.photosDir));

  app.use(router);

  // 3. Create HTTP server
  const server = createServer(app);

  // 4. Initialize WebSocket server
  initWebSocketServer(server);

  // 5. Rebuild or recover
  if (config.rebuildDb) {
    log.info("REBUILD_DB=true — wiping DB and rebuilding from blockchain...");
    try {
      await rebuildFromChain();
    } catch (err) {
      log.error({ error: (err as Error).message }, "DB rebuild from chain failed");
    }
  }

  // 6. Backfill missed blockchain events (since last processed block)
  await backfillEvents();

  // 7. Recover games from DB (active + registration-phase) after backfill,
  // so in-memory timers/loops start from the freshest on-chain-synced state.
  recoverGames();
  recoverSimulation();

  // 8. Start live event listener
  await startEventListener();

  // 9. Start HTTP server
  server.listen(config.port, config.host, () => {
    log.info(
      { host: config.host, port: config.port },
      `Server listening on http://${config.host}:${config.port}`
    );
    log.info(`WebSocket available at ws://${config.host}:${config.port}/ws`);
    log.info(`Health check: http://${config.host}:${config.port}/health`);
  });

  // Graceful shutdown
  const shutdown = async (signal: string) => {
    log.info({ signal }, "Shutting down...");

    await stopEventListener();
    cleanupAll();

    server.close(() => {
      log.info("HTTP server closed");
    });

    await closeProviders();
    log.info("Shutdown complete");
    process.exit(0);
  };

  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main().catch((err) => {
  log.fatal({ error: err.message, stack: err.stack }, "Fatal error during startup");
  process.exit(1);
});
