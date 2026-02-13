import { Router } from "express";
import type { Request, Response, NextFunction } from "express";
import { stopSimulation, getSimulation, deploySimulation } from "../simulation/simulator.js";
import { createLogger } from "../utils/logger.js";
import type { DeploySimulationConfig } from "../simulation/types.js";
import { walletAuth } from "./middleware.js";
import type { AuthenticatedRequest } from "./middleware.js";
import { getOperatorWallet } from "../blockchain/client.js";

const log = createLogger("simulationApi");

const simulationRouter = Router();

function requireOperator(req: Request, res: Response, next: NextFunction): void {
  const authReq = req as AuthenticatedRequest;
  const operatorAddress = getOperatorWallet().address.toLowerCase();
  if (authReq.playerAddress !== operatorAddress) {
    res.status(403).json({ error: "Operator only" });
    return;
  }
  next();
}

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
simulationRouter.post("/api/simulation/stop", walletAuth, requireOperator, (_req: Request, res: Response) => {
  const stopped = stopSimulation();
  res.json({ stopped });
});

/**
 * POST /api/simulation/deploy — unified: create game on-chain, register simulated
 * players, then let the server lifecycle auto-start and simulate.
 *
 * Body: { playerCount?, centerLat?, centerLng?, initialRadiusMeters?,
 *         speedMultiplier?, title?, entryFeeWei?, registrationDelaySeconds?,
 *         gameStartDelaySeconds?, maxDurationSeconds? }
 */
simulationRouter.post("/api/simulation/deploy", walletAuth, requireOperator, (req: Request, res: Response) => {
  try {
    const body = req.body as Partial<DeploySimulationConfig>;

    const cfg: DeploySimulationConfig = {
      playerCount: Math.min(5, Math.max(3, body.playerCount ?? 5)),
      centerLat: body.centerLat ?? 19.43527887514233,
      centerLng: body.centerLng ?? -99.12806514424551,
      initialRadiusMeters: body.initialRadiusMeters ?? 500,
      speedMultiplier: Math.min(50, Math.max(1, body.speedMultiplier ?? 1)),
      title: body.title ?? "Simulation Game",
      entryFeeWei: body.entryFeeWei ?? "0",
      baseRewardWei: body.baseRewardWei ?? "0",
      registrationDelaySeconds: Math.min(600, Math.max(5, body.registrationDelaySeconds ?? 30)),
      gameStartDelaySeconds: Math.min(600, Math.max(2, body.gameStartDelaySeconds ?? 15)),
      maxDurationSeconds: Math.min(7200, Math.max(60, body.maxDurationSeconds ?? 600)),
    };

    const sim = deploySimulation(cfg);

    res.json({
      gameId: sim.gameId,
      status: sim.getStatus(),
      message: `Game will be created on-chain. Registration open for ${cfg.registrationDelaySeconds}s, then auto-start.`,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    log.error({ error: message }, "Failed to deploy simulation");
    res.status(400).json({ error: message });
  }
});

export { simulationRouter };
