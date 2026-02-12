import { useState, useEffect, useCallback } from 'react';
import { loadGame, loadGameEvents } from '../lib/contract';
import { onContractEvent } from '../lib/contractEvents';
import type { Game } from '../types/game';

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

  return { game, loading, error };
}
