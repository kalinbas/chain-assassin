import { Router } from "express";
import multer from "multer";
import { walletAuth } from "./middleware.js";
import { healthCheck, gameStatus, listGames, gameDetail, playerInfo, submitKill, submitLocation, submitCheckin, submitHeartbeat, triggerAutoStart, uploadPhoto, getPhotos } from "./handlers.js";
import { simulationRouter } from "./simulationRoutes.js";
import { config } from "../config.js";

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: config.maxPhotoSizeMb * 1024 * 1024 },
});

const router = Router();

// Simulation routes are opt-in and disabled by default.
if (config.enableSimulationApi) {
  router.use(simulationRouter);
}

// Public
router.get("/health", healthCheck);
router.get("/api/games", listGames);
router.get("/api/games/:gameId/status", gameStatus);
router.get("/api/games/:gameId/player/:address", playerInfo);
router.get("/api/games/:gameId/photos", getPhotos);
router.get("/api/games/:gameId", gameDetail);

// Authenticated
router.post("/api/games/:gameId/kill", walletAuth, submitKill);
router.post("/api/games/:gameId/location", walletAuth, submitLocation);
router.post("/api/games/:gameId/checkin", walletAuth, submitCheckin);
router.post("/api/games/:gameId/heartbeat", walletAuth, submitHeartbeat);
router.post("/api/games/:gameId/photos", walletAuth, upload.single("photo"), uploadPhoto);

// Admin (operator-only, uses wallet auth)
router.post("/api/admin/check-auto-start", walletAuth, triggerAutoStart);

export { router };
