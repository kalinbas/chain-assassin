import { useEffect, useState } from 'react';
import { loadGame } from '../lib/api';
import type { Game } from '../types/game';

const gameCache = new Map<number, Game | null>();
const inFlightById = new Map<number, Promise<Game | null>>();

function loadGameFresh(id: number): Promise<Game | null> {
  const inFlight = inFlightById.get(id);
  if (inFlight) {
    return inFlight;
  }

  const request = loadGame(id)
    .then((gameData) => {
      gameCache.set(id, gameData);
      return gameData;
    })
    .finally(() => {
      inFlightById.delete(id);
    });

  inFlightById.set(id, request);
  return request;
}

export function useGame(id: number) {
  const [game, setGame] = useState<Game | null>(() => (id <= 0 ? null : (gameCache.get(id) ?? null)));
  const [loading, setLoading] = useState(() => id > 0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);

    if (id <= 0) {
      setGame(null);
      setLoading(false);
      return;
    }

    const cached = gameCache.get(id);
    if (cached !== undefined) {
      setGame(cached);
    }

    setLoading(true);

    loadGameFresh(id)
      .then((gameData) => {
        if (cancelled) return;
        setGame(gameData);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load game:', err);
        setError('Could not fetch game data.');
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [id]);

  return { game, loading, error };
}
