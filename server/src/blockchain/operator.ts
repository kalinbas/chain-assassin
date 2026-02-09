import { ethers } from "ethers";
import { getWriteContract } from "./contract.js";
import { insertOperatorTx, updateOperatorTx } from "../db/queries.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("operator");

/**
 * Sequential transaction queue to prevent nonce collisions.
 * All operator txs go through this queue.
 */
let txQueue: Promise<void> = Promise.resolve();
let pendingNonce: number | null = null;

async function getNextNonce(): Promise<number> {
  const wallet = getWriteContract().runner as ethers.Wallet;
  if (pendingNonce === null) {
    pendingNonce = await wallet.getNonce("pending");
  } else {
    pendingNonce++;
  }
  return pendingNonce;
}

function resetNonce(): void {
  pendingNonce = null;
}

interface TxResult {
  txHash: string;
  receipt: ethers.TransactionReceipt;
}

/**
 * Enqueue an operator transaction. Ensures sequential nonce management.
 */
async function enqueueTx(
  gameId: number,
  action: string,
  params: Record<string, unknown>,
  txFn: (nonce: number) => Promise<ethers.TransactionResponse>
): Promise<TxResult> {
  return new Promise((resolve, reject) => {
    txQueue = txQueue.then(async () => {
      const txLogId = insertOperatorTx({
        gameId,
        action,
        txHash: null,
        status: "pending",
        createdAt: Math.floor(Date.now() / 1000),
        confirmedAt: null,
        error: null,
        params: JSON.stringify(params),
      });

      try {
        const nonce = await getNextNonce();
        log.info({ gameId, action, nonce, params }, "Submitting operator tx");

        const tx = await txFn(nonce);
        updateOperatorTx(txLogId, { txHash: tx.hash, status: "submitted" });
        log.info({ gameId, action, txHash: tx.hash }, "Tx submitted");

        const receipt = await tx.wait();
        if (!receipt || receipt.status === 0) {
          resetNonce();
          const error = "Transaction reverted";
          updateOperatorTx(txLogId, { status: "failed", error });
          reject(new Error(error));
          return;
        }

        updateOperatorTx(txLogId, {
          status: "confirmed",
          confirmedAt: Math.floor(Date.now() / 1000),
        });
        log.info(
          { gameId, action, txHash: tx.hash, gasUsed: receipt.gasUsed.toString() },
          "Tx confirmed"
        );
        resolve({ txHash: tx.hash, receipt });
      } catch (err) {
        resetNonce();
        const error = err instanceof Error ? err.message : String(err);
        updateOperatorTx(txLogId, { status: "failed", error });
        log.error({ gameId, action, error }, "Tx failed");
        reject(err);
      }
    });
  });
}

// ============ Operator Actions ============

/**
 * Start a game on-chain.
 */
export async function startGame(gameId: number): Promise<TxResult> {
  return enqueueTx(gameId, "startGame", { gameId }, async (nonce) => {
    const contract = getWriteContract();
    return contract.startGame(gameId, { nonce });
  });
}

/**
 * Record a kill on-chain.
 */
export async function recordKill(
  gameId: number,
  hunter: string,
  target: string
): Promise<TxResult> {
  return enqueueTx(
    gameId,
    "recordKill",
    { gameId, hunter, target },
    async (nonce) => {
      const contract = getWriteContract();
      return contract.recordKill(gameId, hunter, target, { nonce });
    }
  );
}

/**
 * Eliminate a player on-chain (e.g., zone violation).
 */
export async function eliminatePlayer(
  gameId: number,
  player: string
): Promise<TxResult> {
  return enqueueTx(
    gameId,
    "eliminatePlayer",
    { gameId, player },
    async (nonce) => {
      const contract = getWriteContract();
      return contract.eliminatePlayer(gameId, player, { nonce });
    }
  );
}

/**
 * End a game on-chain with winners and top killer.
 */
export async function endGame(
  gameId: number,
  winner1: string,
  winner2: string,
  winner3: string,
  topKiller: string
): Promise<TxResult> {
  return enqueueTx(
    gameId,
    "endGame",
    { gameId, winner1, winner2, winner3, topKiller },
    async (nonce) => {
      const contract = getWriteContract();
      return contract.endGame(gameId, winner1, winner2, winner3, topKiller, {
        nonce,
      });
    }
  );
}
