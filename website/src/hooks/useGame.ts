import { useState, useEffect } from 'react';
import { loadGame, loadGameEvents } from '../lib/contract';
import type { Game } from '../types/game';

export function useGame(id: number) {
  const [game, setGame] = useState<Game | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
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

  return { game, loading, error };
}
