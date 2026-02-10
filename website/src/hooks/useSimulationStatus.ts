import { useState, useEffect } from 'react';
import { SERVER_URL } from '../config/server';

export interface SimulationStatus {
  gameId: number;
  title: string;
  phase: string;
  playerCount: number;
  aliveCount: number;
  killCount: number;
  elapsedSeconds: number;
  speedMultiplier: number;
}

export function useSimulationStatus(pollMs = 5000): SimulationStatus | null {
  const [status, setStatus] = useState<SimulationStatus | null>(null);

  useEffect(() => {
    let active = true;

    const poll = async () => {
      try {
        const res = await fetch(`${SERVER_URL}/api/simulation/status`);
        if (!res.ok) { setStatus(null); return; }
        const data = await res.json();
        if (active) setStatus(data ?? null);
      } catch {
        if (active) setStatus(null);
      }
    };

    poll();
    const timer = setInterval(poll, pollMs);
    return () => { active = false; clearInterval(timer); };
  }, [pollMs]);

  return status;
}
