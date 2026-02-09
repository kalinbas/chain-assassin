import type { Request, Response, NextFunction } from "express";
import { validateApiAuth } from "../utils/crypto.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("auth");

/**
 * Express middleware that verifies wallet signature auth headers.
 *
 * Required headers:
 *   X-Address: wallet address
 *   X-Signature: EIP-191 signature
 *   X-Message: signed message ("chain-assassin:{timestamp}")
 *
 * On success, sets `req.playerAddress` on the request object.
 */
export function walletAuth(req: Request, res: Response, next: NextFunction): void {
  const address = req.headers["x-address"] as string | undefined;
  const signature = req.headers["x-signature"] as string | undefined;
  const message = req.headers["x-message"] as string | undefined;

  if (!address || !signature || !message) {
    res.status(401).json({ error: "Missing auth headers (X-Address, X-Signature, X-Message)" });
    return;
  }

  const result = validateApiAuth(address, signature, message);
  if (!result.valid) {
    log.warn({ address, error: result.error }, "Auth failed");
    res.status(401).json({ error: result.error });
    return;
  }

  // Attach verified address to request
  (req as AuthenticatedRequest).playerAddress = address.toLowerCase();
  next();
}

export interface AuthenticatedRequest extends Request {
  playerAddress: string;
}
