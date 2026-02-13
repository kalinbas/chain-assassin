import { useEffect, useState } from 'react';
import { loadAllGames } from '../lib/api';
import type { Game } from '../types/game';

interface GamesState {
  games: Game[];
  loading: boolean;
  error: string | null;
}

const POLL_INTERVAL_MS = 60_000;

const listeners = new Set<(state: GamesState) => void>();

let state: GamesState = {
  games: [],
  loading: true,
  error: null,
};

let inFlightFetch: Promise<void> | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let lastFetchAtMs = 0;

function notifyListeners() {
  for (const listener of listeners) {
    listener(state);
  }
}

function setState(next: Partial<GamesState>) {
  state = { ...state, ...next };
  notifyListeners();
}

function fetchGames() {
  if (inFlightFetch) {
    return inFlightFetch;
  }

  inFlightFetch = loadAllGames()
    .then((games) => {
      setState({ games, error: null, loading: false });
    })
    .catch((err) => {
      console.error('Failed to load games:', err);
      setState({ error: 'Failed to load games. Please try again later.', loading: false });
    })
    .finally(() => {
      inFlightFetch = null;
      lastFetchAtMs = Date.now();
    });

  return inFlightFetch;
}

function startPolling() {
  if (pollTimer) {
    return;
  }

  if (state.loading || Date.now() - lastFetchAtMs >= POLL_INTERVAL_MS) {
    void fetchGames();
  }
  pollTimer = setInterval(() => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') {
      return;
    }
    void fetchGames();
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (!pollTimer) {
    return;
  }
  clearInterval(pollTimer);
  pollTimer = null;
}

export function useGames() {
  const [games, setGames] = useState(state.games);
  const [loading, setLoading] = useState(state.loading);
  const [error, setError] = useState(state.error);

  useEffect(() => {
    const listener = (next: GamesState) => {
      setGames(next.games);
      setLoading(next.loading);
      setError(next.error);
    };

    listeners.add(listener);
    listener(state);

    startPolling();

    const onVisibilityOrFocus = () => {
      if (typeof document !== 'undefined' && document.visibilityState !== 'visible') {
        return;
      }
      void fetchGames();
    };

    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisibilityOrFocus);
    }
    if (typeof window !== 'undefined') {
      window.addEventListener('focus', onVisibilityOrFocus);
      window.addEventListener('online', onVisibilityOrFocus);
    }

    return () => {
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisibilityOrFocus);
      }
      if (typeof window !== 'undefined') {
        window.removeEventListener('focus', onVisibilityOrFocus);
        window.removeEventListener('online', onVisibilityOrFocus);
      }
      listeners.delete(listener);
      if (listeners.size === 0) {
        stopPolling();
      }
    };
  }, []);

  return { games, loading, error };
}
