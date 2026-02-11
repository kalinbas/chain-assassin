import { useState, useEffect, useCallback } from 'react';
import { loadGame, loadGameEvents } from '../lib/contract';
import { onContractEvent } from '../lib/contractEvents';
import { SERVER_URL } from '../config/server';
import type { Game, SubPhase } from '../types/game';

export function useGame(id: number) {
  const [game, setGame] = useState<Game | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchGame = useCallback(() => {
    if (id <= 0) {
      setLoading(false);
      return;
    }
    Promise.all([loadGame(id), loadGameEvents(id)])
      .then(([gameData, events]) => {
        if (gameData) {
          gameData.activity = events;
          setGame(gameData);
          document.title = `${gameData.title} — Chain Assassin`;
        }
      })
      .catch((err) => {
        console.error('Failed to load game:', err);
        setError('Could not fetch game data from the blockchain.');
      })
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    fetchGame();
    if (id <= 0) return;
    return onContractEvent(fetchGame);
  }, [id, fetchGame]);

  // For active games, poll server for sub-phase info
  useEffect(() => {
    if (!game || game.phase !== 'active') return;

    let active = true;

    const poll = async () => {
      try {
        const res = await fetch(`${SERVER_URL}/api/games/${game.id}/status`);
        if (!res.ok) return;
        const data = await res.json();
        if (active) {
          setGame((prev) => prev ? {
            ...prev,
            subPhase: data.subPhase as SubPhase,
            checkinEndsAt: data.checkinEndsAt,
            pregameEndsAt: data.pregameEndsAt,
            playerCount: data.playerCount,
            aliveCount: data.aliveCount,
          } : prev);
        }
      } catch {
        // Server may not be running — leave subPhase undefined
      }
    };

    poll();
    const timer = setInterval(poll, 5000);
    return () => { active = false; clearInterval(timer); };
  }, [game?.id, game?.phase]);

  return { game, loading, error };
}
