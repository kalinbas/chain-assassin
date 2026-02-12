import { useState, useEffect, useCallback } from 'react';
import { loadGame } from '../lib/api';
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
    loadGame(id)
      .then((gameData) => {
        if (gameData) {
          setGame(gameData);
          document.title = `${gameData.title} — Chain Assassin`;
        }
      })
      .catch((err) => {
        console.error('Failed to load game:', err);
        setError('Could not fetch game data.');
      })
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    fetchGame();
    if (id <= 0) return;
    const interval = setInterval(fetchGame, 30000);
    return () => clearInterval(interval);
  }, [id, fetchGame]);

  return { game, loading, error };
}
