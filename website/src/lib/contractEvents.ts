import { Contract, JsonRpcProvider } from 'ethers';
import { CONTRACT_ADDRESS, CONTRACT_ABI, RPC_URL } from '../config/constants';

type Callback = () => void;

const listeners = new Set<Callback>();
let started = false;

/**
 * Subscribe to contract event notifications. The callback is invoked whenever
 * a relevant on-chain event is detected (GameCreated, PlayerRegistered,
 * GameStarted, GameEnded, GameCancelled). Callers should refetch their data.
 *
 * Returns an unsubscribe function.
 */
export function onContractEvent(cb: Callback): () => void {
  listeners.add(cb);
  startListening();
  return () => { listeners.delete(cb); };
}

function notify(): void {
  for (const cb of listeners) {
    try { cb(); } catch { /* ignore */ }
  }
}

function startListening(): void {
  if (started) return;
  started = true;

  const provider = new JsonRpcProvider(RPC_URL);
  const contract = new Contract(CONTRACT_ADDRESS, CONTRACT_ABI, provider);
  let lastBlock = 0;

  const poll = async () => {
    try {
      const current = await provider.getBlockNumber();
      if (lastBlock === 0) {
        lastBlock = current;
        return;
      }
      if (current <= lastBlock) return;

      // Query only the new blocks (max 10 block range for free tier)
      const from = lastBlock + 1;
      const to = current;
      lastBlock = current;

      const events = await contract.queryFilter('*', from, to);
      if (events.length > 0) {
        notify();
      }
    } catch {
      // ignore RPC errors silently
    }
  };

  setInterval(poll, 10000);
  poll();
}
