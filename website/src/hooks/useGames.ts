import { useState, useEffect, useCallback } from 'react';
import { loadAllGames } from '../lib/api';
import type { Game } from '../types/game';

export function useGames() {
  const [games, setGames] = useState<Game[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchGames = useCallback(() => {
    loadAllGames()
      .then(setGames)
      .catch((err) => {
        console.error('Failed to load games:', err);
        setError('Failed to load games. Please try again later.');
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchGames();
    const interval = setInterval(fetchGames, 30000);
    return () => clearInterval(interval);
  }, [fetchGames]);

  return { games, loading, error };
}
