import { useState, useEffect } from 'react';
import { loadAllGames } from '../lib/contract';
import type { Game } from '../types/game';

export function useGames() {
  const [games, setGames] = useState<Game[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadAllGames()
      .then(setGames)
      .catch((err) => {
        console.error('Failed to load games:', err);
        setError('Failed to load games. Please try again later.');
      })
      .finally(() => setLoading(false));
  }, []);

  return { games, loading, error };
}
