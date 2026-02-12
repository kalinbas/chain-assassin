import { useState, useEffect, useRef, useCallback } from 'react';
import { useGames } from './useGames';
import { SERVER_WS_URL } from '../config/server';

export interface ActiveGameStatus {
  gameId: number;
  title: string;
  phase: string;
  playerCount: number;
  aliveCount: number;
  killCount: number;
  checkinEndsAt: number | null;
  pregameEndsAt: number | null;
  checkedInCount: number;
}

export function useActiveGame(): ActiveGameStatus | null {
  const { games } = useGames();
  const [status, setStatus] = useState<ActiveGameStatus | null>(null);
  const wsRef = useRef<WebSocket | null>(null);

  const activeGame = games.find((g) => g.phase === 'active');

  const connect = useCallback((gameId: number, title: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;

    const ws = new WebSocket(`${SERVER_WS_URL}/ws`);
    wsRef.current = ws;

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'spectate', gameId }));
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'spectate:init') {
          const phase = msg.subPhase === 'game' ? 'active' : msg.subPhase ?? 'active';
          setStatus({
            gameId,
            title,
            phase,
            playerCount: msg.playerCount,
            aliveCount: msg.aliveCount,
            killCount: (msg.leaderboard as { kills: number }[])?.reduce((sum: number, e: { kills: number }) => sum + e.kills, 0) ?? 0,
            checkinEndsAt: msg.checkinEndsAt ?? null,
            pregameEndsAt: msg.pregameEndsAt ?? null,
            checkedInCount: msg.checkedInCount ?? 0,
          });
        } else if (msg.type === 'spectator:positions') {
          setStatus((prev) => prev ? { ...prev, aliveCount: msg.aliveCount } : prev);
        } else if (msg.type === 'leaderboard:update') {
          const entries = msg.entries as { kills: number }[];
          setStatus((prev) => prev ? {
            ...prev,
            killCount: entries.reduce((sum, e) => sum + e.kills, 0),
          } : prev);
        } else if (msg.type === 'game:checkin_started') {
          setStatus((prev) => prev ? { ...prev, phase: 'checkin', checkinEndsAt: msg.checkinEndsAt ?? null } : prev);
        } else if (msg.type === 'checkin:update') {
          setStatus((prev) => prev ? { ...prev, checkedInCount: msg.checkedInCount ?? prev.checkedInCount } : prev);
        } else if (msg.type === 'game:pregame_started') {
          setStatus((prev) => prev ? { ...prev, phase: 'pregame', pregameEndsAt: msg.pregameEndsAt ?? null, playerCount: msg.playerCount ?? prev.playerCount } : prev);
        } else if (msg.type === 'game:started_broadcast') {
          setStatus((prev) => prev ? { ...prev, phase: 'active', playerCount: msg.playerCount } : prev);
        } else if (msg.type === 'game:ended') {
          setStatus(null);
        }
      } catch {
        // ignore parse errors
      }
    };

    ws.onclose = () => {
      // Don't reconnect — useEffect will handle cleanup/reconnect on dependency change
    };

    ws.onerror = () => {
      ws.close();
    };
  }, []);

  useEffect(() => {
    if (!activeGame) {
      setStatus(null);
      return;
    }

    connect(activeGame.id, activeGame.title);

    return () => {
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [activeGame?.id, connect]);

  return status;
}
