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

  // Use a separate provider instance with polling enabled for event subscriptions.
  // ethers v6 JsonRpcProvider polls automatically (default 4s).
  const provider = new JsonRpcProvider(RPC_URL, undefined, { polling: true, pollingInterval: 5000 });
  const contract = new Contract(CONTRACT_ADDRESS, CONTRACT_ABI, provider);

  contract.on('GameCreated', () => notify());
  contract.on('PlayerRegistered', () => notify());
  contract.on('GameStarted', () => notify());
  contract.on('GameEnded', () => notify());
  contract.on('GameCancelled', () => notify());
}
