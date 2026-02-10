import { ethers } from "ethers";
import { config } from "../config.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("blockchain");

let httpProvider: ethers.JsonRpcProvider;
let wsProvider: ethers.WebSocketProvider;
let operatorWallet: ethers.Wallet;

/**
 * Initialize HTTP JSON-RPC provider (for reads + tx sending).
 */
export function getHttpProvider(): ethers.JsonRpcProvider {
  if (!httpProvider) {
    httpProvider = new ethers.JsonRpcProvider(config.rpcUrl, config.chainId);
    log.info({ rpcUrl: config.rpcUrl, chainId: config.chainId }, "HTTP provider initialized");
  }
  return httpProvider;
}

/**
 * Initialize WebSocket provider (for event listening).
 */
export function getWsProvider(): ethers.WebSocketProvider {
  if (!wsProvider) {
    wsProvider = new ethers.WebSocketProvider(config.rpcWsUrl, config.chainId);
    // Prevent unhandled WS error from crashing the process
    const ws = wsProvider.websocket as unknown as { on?: (event: string, handler: (err: Error) => void) => void };
    if (ws.on) {
      ws.on("error", (err: Error) => {
        log.warn({ error: err.message }, "WebSocket provider error (non-fatal)");
      });
    }
    log.info({ rpcWsUrl: config.rpcWsUrl }, "WebSocket provider initialized");
  }
  return wsProvider;
}

/**
 * Get the operator wallet (signer) connected to HTTP provider.
 */
export function getOperatorWallet(): ethers.Wallet {
  if (!operatorWallet) {
    operatorWallet = new ethers.Wallet(
      config.operatorPrivateKey,
      getHttpProvider()
    );
    log.info(
      { address: operatorWallet.address },
      "Operator wallet initialized"
    );
  }
  return operatorWallet;
}

/**
 * Cleanup providers on shutdown.
 */
export async function closeProviders(): Promise<void> {
  if (wsProvider) {
    await wsProvider.destroy();
    log.info("WebSocket provider closed");
  }
}
