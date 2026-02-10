import { Router } from "express";
import { walletAuth } from "./middleware.js";
import { healthCheck, gameStatus, submitKill, submitLocation, submitCheckin, triggerAutoStart } from "./handlers.js";
import { simulationRouter } from "./simulationRoutes.js";

const router = Router();

// Simulation (no auth)
router.use(simulationRouter);

// Public
router.get("/health", healthCheck);
router.get("/api/games/:gameId/status", gameStatus);

// Authenticated
router.post("/api/games/:gameId/kill", walletAuth, submitKill);
router.post("/api/games/:gameId/location", walletAuth, submitLocation);
router.post("/api/games/:gameId/checkin", walletAuth, submitCheckin);

// Admin (operator-only, uses wallet auth)
router.post("/api/admin/check-auto-start", walletAuth, triggerAutoStart);

export { router };
