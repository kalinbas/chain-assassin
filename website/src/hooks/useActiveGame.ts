import { useState, useEffect } from 'react';
import { useGames } from './useGames';
import { SERVER_URL } from '../config/server';

export interface ActiveGameStatus {
  gameId: number;
  title: string;
  phase: string;
  playerCount: number;
  aliveCount: number;
  killCount: number;
}

export function useActiveGame(pollMs = 5000): ActiveGameStatus | null {
  const { games } = useGames();
  const [status, setStatus] = useState<ActiveGameStatus | null>(null);

  // useGames auto-refreshes on contract events, so this will pick up
  // registration → active transitions without timers.
  const activeGame = games.find((g) => g.phase === 'active');

  useEffect(() => {
    if (!activeGame) {
      setStatus(null);
      return;
    }

    let active = true;

    const poll = async () => {
      try {
        const res = await fetch(`${SERVER_URL}/api/games/${activeGame.id}/status`);
        if (!res.ok) { if (active) setStatus(null); return; }
        const data = await res.json();
        if (active) {
          setStatus({
            gameId: activeGame.id,
            title: activeGame.title,
            phase: data.subPhase === 'game' ? 'active' : data.subPhase ?? 'active',
            playerCount: data.playerCount,
            aliveCount: data.aliveCount,
            killCount: data.leaderboard?.reduce((sum: number, e: { kills: number }) => sum + e.kills, 0) ?? 0,
          });
        }
      } catch {
        if (active) setStatus(null);
      }
    };

    poll();
    const timer = setInterval(poll, pollMs);
    return () => { active = false; clearInterval(timer); };
  }, [activeGame?.id, pollMs]);

  return status;
}
