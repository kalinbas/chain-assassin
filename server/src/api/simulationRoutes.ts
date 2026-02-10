import { Router } from "express";
import type { Request, Response } from "express";
import { startSimulation, stopSimulation, getSimulation } from "../simulation/simulator.js";
import { getGame, getAlivePlayers, getPlayers, getLatestLocationPing, getZoneShrinks } from "../db/queries.js";
import { getGameStatus } from "../game/manager.js";
import { contractCoordToDegrees } from "../utils/geo.js";
import { getLeaderboard } from "../game/leaderboard.js";
import { createLogger } from "../utils/logger.js";
import type { SimulationConfig } from "../simulation/types.js";

const log = createLogger("simulationApi");

const simulationRouter = Router();

/**
 * POST /api/simulation/start — start a new simulation.
 */
simulationRouter.post("/api/simulation/start", (req: Request, res: Response) => {
  try {
    const body = req.body as Partial<SimulationConfig>;

    const cfg: SimulationConfig = {
      playerCount: Math.min(50, Math.max(3, body.playerCount ?? 10)),
      centerLat: body.centerLat ?? 37.7749,
      centerLng: body.centerLng ?? -122.4194,
      initialRadiusMeters: body.initialRadiusMeters ?? 500,
      speedMultiplier: Math.min(50, Math.max(1, body.speedMultiplier ?? 5)),
      useOnChain: body.useOnChain ?? false,
      title: body.title ?? "Simulation",
      entryFeeWei: body.entryFeeWei ?? "0",
    };

    const sim = startSimulation(cfg);

    res.json({
      gameId: sim.gameId,
      status: sim.getStatus(),
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    log.error({ error: message }, "Failed to start simulation");
    res.status(400).json({ error: message });
  }
});

/**
 * GET /api/simulation/status — get current simulation status.
 */
simulationRouter.get("/api/simulation/status", (_req: Request, res: Response) => {
  const sim = getSimulation();
  if (!sim) {
    res.json(null);
    return;
  }
  res.json(sim.getStatus());
});

/**
 * POST /api/simulation/stop — stop the current simulation.
 */
simulationRouter.post("/api/simulation/stop", (_req: Request, res: Response) => {
  const stopped = stopSimulation();
  res.json({ stopped });
});

/**
 * GET /api/simulation/game — get the simulated game in website Game format.
 */
simulationRouter.get("/api/simulation/game", (req: Request, res: Response) => {
  const gameId = parseInt(req.query.gameId as string, 10);
  if (isNaN(gameId)) {
    res.status(400).json({ error: "gameId query parameter required" });
    return;
  }

  const game = getGame(gameId);
  if (!game) {
    res.status(404).json({ error: "Game not found" });
    return;
  }

  const status = getGameStatus(gameId);
  const players = getPlayers(gameId);
  const phaseNames = ["registration", "active", "ended", "cancelled"];

  // Build website-compatible Game object
  const websiteGame = {
    id: game.gameId,
    title: game.title,
    entryFee: Number(game.entryFee) / 1e18,
    entryFeeWei: game.entryFee.toString(),
    location: "Simulation",
    date: new Date((game.startedAt ?? game.createdAt) * 1000).toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
    }),
    players: players.length,
    maxPlayers: game.maxPlayers,
    minPlayers: game.minPlayers,
    registrationDeadline: new Date(game.registrationDeadline * 1000).toLocaleString(),
    expiryDeadline: new Date(game.expiryDeadline * 1000).toLocaleString(),
    centerLat: contractCoordToDegrees(game.centerLat),
    centerLng: contractCoordToDegrees(game.centerLng),
    meetingLat: contractCoordToDegrees(game.meetingLat),
    meetingLng: contractCoordToDegrees(game.meetingLng),
    bps: {
      first: game.bps1st,
      second: game.bps2nd,
      third: game.bps3rd,
      kills: game.bpsKills,
      creator: 0,
      platform: game.bpsPlatform,
    },
    zoneShrinks: getZoneShrinks(gameId),
    phase: phaseNames[game.phase] ?? "registration",
    creator: game.creator,
    createdAt: game.createdAt,
    winner1: game.winner1 ?? "0x0000000000000000000000000000000000000000",
    winner2: game.winner2 ?? "0x0000000000000000000000000000000000000000",
    winner3: game.winner3 ?? "0x0000000000000000000000000000000000000000",
    topKiller: game.topKiller ?? "0x0000000000000000000000000000000000000000",
    activity: [],
  };

  res.json({ game: websiteGame });
});

export { simulationRouter };
