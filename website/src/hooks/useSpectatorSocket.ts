import { useEffect, useReducer, useRef, useCallback } from 'react';
import { SERVER_WS_URL } from '../config/server';
import { trackEvent } from '../lib/analytics';

export interface SpectatorPlayer {
  playerNumber: number;
  lat: number | null;
  lng: number | null;
  isAlive: boolean;
  kills: number;
}

export interface SpectatorEvent {
  type: string;
  text: string;
  timestamp: number;
  meta?: { itemId?: string };
}

export interface KillFlash {
  lat: number;
  lng: number;
  timestamp: number;
}

export interface PingCircle {
  lat: number;
  lng: number;
  radius: number;
  expiresAt: number;
  type: 'target' | 'hunter';
}

export interface SpectatorState {
  connected: boolean;
  connectionState: 'connecting' | 'connected' | 'reconnecting';
  reconnectAttempt: number;
  nextRetryAt: number | null;
  lastMessageAt: number | null;
  isStale: boolean;
  phase: string | null;
  subPhase: string | null;
  checkinEndsAt: number | null;
  pregameEndsAt: number | null;
  zone: {
    centerLat: number;
    centerLng: number;
    currentRadiusMeters: number;
    nextShrinkAt?: number | null;
    nextRadiusMeters?: number | null;
  } | null;
  players: SpectatorPlayer[];
  leaderboard: { playerNumber: number; kills: number; isAlive: boolean }[];
  events: SpectatorEvent[];
  aliveCount: number;
  playerCount: number;
  checkedInCount: number;
  winners: { winner1: number; winner2: number; winner3: number; topKiller: number } | null;
  killFlashes: KillFlash[];
  trails: Record<number, { lat: number; lng: number; timestamp: number }[]>;
  huntLinks: { hunter: number; target: number }[];
  pingCircles: PingCircle[];
  totalKills: number;
  gameStartedAt: number | null;
}

const initialState: SpectatorState = {
  connected: false,
  connectionState: 'connecting',
  reconnectAttempt: 0,
  nextRetryAt: null,
  lastMessageAt: null,
  isStale: false,
  phase: null,
  subPhase: null,
  checkinEndsAt: null,
  pregameEndsAt: null,
  zone: null,
  players: [],
  leaderboard: [],
  events: [],
  aliveCount: 0,
  playerCount: 0,
  checkedInCount: 0,
  winners: null,
  killFlashes: [],
  trails: {},
  huntLinks: [],
  pingCircles: [],
  totalKills: 0,
  gameStartedAt: null,
};

type Action =
  | { type: 'connecting' }
  | { type: 'connected' }
  | { type: 'disconnected' }
  | { type: 'reconnecting'; payload: { attempt: number; nextRetryAt: number } }
  | { type: 'message_received' }
  | { type: 'mark_stale' }
  | { type: 'init'; payload: Record<string, unknown> }
  | { type: 'positions'; payload: Record<string, unknown> }
  | { type: 'kill'; payload: Record<string, unknown> }
  | { type: 'eliminated'; payload: Record<string, unknown> }
  | { type: 'zone_shrink'; payload: Record<string, unknown> }
  | { type: 'leaderboard'; payload: Record<string, unknown> }
  | { type: 'player_registered'; payload: Record<string, unknown> }
  | { type: 'checkin_started'; payload: Record<string, unknown> }
  | { type: 'pregame_started'; payload: Record<string, unknown> }
  | { type: 'game_started'; payload: Record<string, unknown> }
  | { type: 'game_ended'; payload: Record<string, unknown> }
  | { type: 'game_cancelled'; payload: Record<string, unknown> }
  | { type: 'item_used'; payload: Record<string, unknown> }
  | { type: 'checkin_update'; payload: Record<string, unknown> };

function addEvent(events: SpectatorEvent[], text: string, type: string, meta?: SpectatorEvent['meta']): SpectatorEvent[] {
  const updated = [{ type, text, timestamp: Date.now(), meta }, ...events];
  return updated.slice(0, 50);
}

function playerLabel(playerNumber: number): string {
  return `Player #${playerNumber}`;
}

function normalizePhase(raw: unknown): string | null {
  if (typeof raw === 'number') {
    if (raw === 0) return 'registration';
    if (raw === 1) return 'active';
    if (raw === 2) return 'ended';
    if (raw === 3) return 'cancelled';
    return null;
  }
  if (typeof raw === 'string' && raw.length > 0) {
    if (/^\d+$/.test(raw)) {
      return normalizePhase(Number(raw));
    }
    return raw;
  }
  return null;
}

function reducer(state: SpectatorState, action: Action): SpectatorState {
  switch (action.type) {
    case 'connecting':
      return {
        ...state,
        connectionState: 'connecting',
        nextRetryAt: null,
      };

    case 'connected':
      return {
        ...state,
        connected: true,
        connectionState: 'connected',
        reconnectAttempt: 0,
        nextRetryAt: null,
        lastMessageAt: Date.now(),
        isStale: false,
      };

    case 'disconnected':
      return {
        ...state,
        connected: false,
      };

    case 'reconnecting':
      return {
        ...state,
        connected: false,
        connectionState: 'reconnecting',
        reconnectAttempt: action.payload.attempt,
        nextRetryAt: action.payload.nextRetryAt,
      };

    case 'message_received':
      return {
        ...state,
        lastMessageAt: Date.now(),
        isStale: false,
      };

    case 'mark_stale':
      if (!state.connected || state.isStale) {
        return state;
      }
      return {
        ...state,
        isStale: true,
      };

    case 'init': {
      const p = action.payload;
      const players = p.players as SpectatorPlayer[];
      const phase = normalizePhase(p.phase) ?? state.phase;
      const subPhase = phase === 'active' ? ((p.subPhase as string | null) ?? null) : null;
      return {
        ...state,
        connected: true,
        phase,
        subPhase,
        checkinEndsAt: subPhase === 'checkin' ? ((p.checkinEndsAt as number | null) ?? null) : null,
        pregameEndsAt: subPhase === 'pregame' ? ((p.pregameEndsAt as number | null) ?? null) : null,
        playerCount: p.playerCount as number,
        aliveCount: p.aliveCount as number,
        checkedInCount: (p.checkedInCount as number) ?? 0,
        leaderboard: p.leaderboard as SpectatorState['leaderboard'],
        zone: p.zone as SpectatorState['zone'],
        players,
        winners: (p.winner1 && Number(p.winner1) !== 0)
          ? { winner1: Number(p.winner1), winner2: Number(p.winner2), winner3: Number(p.winner3), topKiller: Number(p.topKiller) }
          : null,
        gameStartedAt: state.gameStartedAt ?? Date.now(),
      };
    }

    case 'positions': {
      const p = action.payload;
      const newPlayers = p.players as SpectatorPlayer[];
      const now = Date.now();

      // Build trails from position history
      const newTrails = { ...state.trails };
      for (const player of newPlayers) {
        if (player.lat != null && player.lng != null && player.isAlive) {
          const key = player.playerNumber;
          const existing = newTrails[key] || [];
          const updated = [...existing, { lat: player.lat, lng: player.lng, timestamp: now }];
          newTrails[key] = updated.slice(-8);
        }
      }

      // Clean up old kill flashes (>3s)
      const killFlashes = state.killFlashes.filter((f) => now - f.timestamp < 3000);

      // Clean up expired ping circles
      const pingCircles = state.pingCircles.filter((c) => c.expiresAt > now);

      return {
        ...state,
        players: newPlayers,
        zone: p.zone as SpectatorState['zone'],
        aliveCount: p.aliveCount as number,
        huntLinks: (p.huntLinks as SpectatorState['huntLinks']) ?? state.huntLinks,
        trails: newTrails,
        killFlashes,
        pingCircles,
      };
    }

    case 'kill': {
      const p = action.payload;
      const hunterNum = p.hunterNumber as number;
      const targetNum = p.targetNumber as number;

      // Find target's last position for kill flash
      const targetPlayer = state.players.find((pl) => pl.playerNumber === targetNum);
      const newFlashes = [...state.killFlashes];
      if (targetPlayer?.lat != null && targetPlayer?.lng != null) {
        newFlashes.push({ lat: targetPlayer.lat, lng: targetPlayer.lng, timestamp: Date.now() });
      }

      return {
        ...state,
        events: addEvent(state.events, `${playerLabel(hunterNum)} eliminated ${playerLabel(targetNum)}`, 'kill'),
        killFlashes: newFlashes,
        totalKills: state.totalKills + 1,
      };
    }

    case 'eliminated': {
      const p = action.payload;
      const num = p.playerNumber as number;
      const reason = p.reason as string;
      if (reason === 'zone_violation') {
        return {
          ...state,
          events: addEvent(state.events, `${playerLabel(num)} eliminated by zone`, 'zone_elim'),
        };
      }
      if (reason === 'heartbeat_timeout') {
        return {
          ...state,
          events: addEvent(state.events, `${playerLabel(num)} eliminated (missed heartbeat)`, 'heartbeat_elim'),
        };
      }
      return state;
    }

    case 'zone_shrink': {
      const p = action.payload;
      return {
        ...state,
        zone: {
          centerLat: p.centerLat as number,
          centerLng: p.centerLng as number,
          currentRadiusMeters: p.currentRadiusMeters as number,
          nextShrinkAt: p.nextShrinkAt as number | null,
          nextRadiusMeters: p.nextRadiusMeters as number | null,
        },
        events: addEvent(state.events, `Zone shrunk to ${p.currentRadiusMeters}m`, 'zone_shrink'),
      };
    }

    case 'leaderboard': {
      const p = action.payload;
      return {
        ...state,
        leaderboard: p.entries as SpectatorState['leaderboard'],
      };
    }

    case 'player_registered': {
      const p = action.payload;
      const nextPlayerCount = (p.playerCount as number) ?? state.playerCount;
      return {
        ...state,
        playerCount: nextPlayerCount,
        aliveCount: Math.max(state.aliveCount, nextPlayerCount),
      };
    }

    case 'checkin_update': {
      const p = action.payload;
      return {
        ...state,
        checkedInCount: (p.checkedInCount as number) ?? state.checkedInCount,
        playerCount: (p.totalPlayers as number) ?? state.playerCount,
      };
    }

    case 'checkin_started': {
      const p = action.payload;
      return {
        ...state,
        phase: 'active',
        subPhase: 'checkin',
        checkinEndsAt: (p.checkinEndsAt as number | null) ?? state.checkinEndsAt,
        pregameEndsAt: null,
        events: addEvent(state.events, 'Check-in started — verify your presence!', 'start'),
      };
    }

    case 'pregame_started': {
      const p = action.payload;
      const duration = (p.pregameDurationSeconds as number) || 180;
      return {
        ...state,
        phase: 'active',
        subPhase: 'pregame',
        checkinEndsAt: null,
        pregameEndsAt: (p.pregameEndsAt as number) ?? Math.floor(Date.now() / 1000) + duration,
        aliveCount: (p.playerCount as number) ?? state.aliveCount,
        events: addEvent(state.events, 'Pregame started — players dispersing!', 'pregame'),
      };
    }

    case 'game_started': {
      const p = action.payload;
      return {
        ...state,
        phase: 'active',
        subPhase: 'game',
        checkinEndsAt: null,
        pregameEndsAt: null,
        playerCount: p.playerCount as number,
        events: addEvent(state.events, 'Game started — hunt begins!', 'start'),
        gameStartedAt: Date.now(),
      };
    }

    case 'game_ended': {
      const p = action.payload;
      return {
        ...state,
        phase: 'ended',
        subPhase: null,
        checkinEndsAt: null,
        pregameEndsAt: null,
        winners: {
          winner1: Number(p.winner1),
          winner2: Number(p.winner2),
          winner3: Number(p.winner3),
          topKiller: Number(p.topKiller),
        },
        events: addEvent(state.events, 'Game ended!', 'end'),
      };
    }

    case 'game_cancelled': {
      return {
        ...state,
        phase: 'cancelled',
        subPhase: null,
        checkinEndsAt: null,
        pregameEndsAt: null,
        events: addEvent(state.events, 'Game cancelled', 'end'),
      };
    }

    case 'item_used': {
      const p = action.payload;
      const num = p.playerNumber as number;
      const itemName = p.itemName as string;
      const itemId = p.itemId as string;

      // Add ping circle if position data is available
      const pingCircles = [...state.pingCircles];
      const pingLat = p.pingLat as number | null;
      const pingLng = p.pingLng as number | null;
      const pingDurationMs = (p.pingDurationMs as number) || 30000;
      const pingRadiusMeters = (p.pingRadiusMeters as number) || 50;

      if (pingLat != null && pingLng != null) {
        pingCircles.push({
          lat: pingLat,
          lng: pingLng,
          radius: pingRadiusMeters,
          expiresAt: Date.now() + pingDurationMs,
          type: itemId === 'ping_target' ? 'target' : 'hunter',
        });
      }

      return {
        ...state,
        events: addEvent(state.events, `${playerLabel(num)} used ${itemName}`, 'item', { itemId }),
        pingCircles,
      };
    }

    default:
      return state;
  }
}

export interface SpectatorSocketState extends SpectatorState {
  refresh: () => void;
}

export function useSpectatorSocket(gameId: number, enabled = true): SpectatorSocketState {
  const [state, dispatch] = useReducer(reducer, initialState);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelayRef = useRef(1000);
  const reconnectAttemptRef = useRef(0);
  const intentionallyClosedRef = useRef(false);

  const connect = useCallback(() => {
    const readyState = wsRef.current?.readyState;
    if (readyState === WebSocket.OPEN || readyState === WebSocket.CONNECTING) {
      return;
    }

    dispatch({ type: 'connecting' });

    const ws = new WebSocket(`${SERVER_WS_URL}/ws`);
    wsRef.current = ws;

    ws.onopen = () => {
      reconnectDelayRef.current = 1000;
      reconnectAttemptRef.current = 0;
      ws.send(JSON.stringify({ type: 'spectate', gameId }));
      dispatch({ type: 'connected' });
      trackEvent('spectator_socket_connected', { game_id: gameId });
    };

    ws.onmessage = (event) => {
      dispatch({ type: 'message_received' });
      try {
        const msg = JSON.parse(event.data);
        switch (msg.type) {
          case 'spectate:init':
            dispatch({ type: 'init', payload: msg });
            break;
          case 'player:registered':
            dispatch({ type: 'player_registered', payload: msg });
            break;
          case 'spectator:positions':
            dispatch({ type: 'positions', payload: msg });
            break;
          case 'kill:recorded':
            dispatch({ type: 'kill', payload: msg });
            break;
          case 'player:eliminated':
            dispatch({ type: 'eliminated', payload: msg });
            break;
          case 'zone:shrink':
            dispatch({ type: 'zone_shrink', payload: msg });
            break;
          case 'leaderboard:update':
            dispatch({ type: 'leaderboard', payload: msg });
            break;
          case 'checkin:update':
            dispatch({ type: 'checkin_update', payload: msg });
            break;
          case 'game:checkin_started':
            dispatch({ type: 'checkin_started', payload: msg });
            break;
          case 'game:pregame_started':
            dispatch({ type: 'pregame_started', payload: msg });
            break;
          case 'game:started_broadcast':
            dispatch({ type: 'game_started', payload: msg });
            break;
          case 'game:ended':
            dispatch({ type: 'game_ended', payload: msg });
            break;
          case 'game:cancelled':
            dispatch({ type: 'game_cancelled', payload: msg });
            break;
          case 'item:used':
            dispatch({ type: 'item_used', payload: msg });
            break;
        }
      } catch {
        // ignore parse errors
      }
    };

    ws.onclose = () => {
      wsRef.current = null;

      if (intentionallyClosedRef.current) {
        intentionallyClosedRef.current = false;
        return;
      }

      const delay = Math.min(reconnectDelayRef.current, 30000);
      reconnectAttemptRef.current += 1;
      const attempt = reconnectAttemptRef.current;
      const nextRetryAt = Date.now() + delay;
      dispatch({ type: 'reconnecting', payload: { attempt, nextRetryAt } });
      trackEvent('spectator_socket_reconnecting', {
        game_id: gameId,
        attempt,
        retry_delay_ms: delay,
      });

      reconnectTimeoutRef.current = setTimeout(() => {
        reconnectTimeoutRef.current = null;
        reconnectDelayRef.current *= 2;
        connect();
      }, delay);
    };

    ws.onerror = () => {
      ws.close();
    };
  }, [gameId]);

  const refresh = useCallback(() => {
    trackEvent('spectator_manual_refresh', { game_id: gameId });

    reconnectDelayRef.current = 1000;
    reconnectAttemptRef.current = 0;

    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }

    if (wsRef.current) {
      intentionallyClosedRef.current = true;
      wsRef.current.close();
      wsRef.current = null;
    }

    connect();
  }, [connect, gameId]);

  useEffect(() => {
    if (!enabled) {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
      if (wsRef.current) {
        intentionallyClosedRef.current = true;
        wsRef.current.close();
        wsRef.current = null;
      }
      dispatch({ type: 'disconnected' });
      return;
    }

    connect();

    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
      if (wsRef.current) {
        intentionallyClosedRef.current = true;
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [connect, enabled]);

  useEffect(() => {
    const lastMessageAt = state.lastMessageAt;
    if (!state.connected || !lastMessageAt) {
      return;
    }

    const staleCheckTimer = window.setInterval(() => {
      if (Date.now() - lastMessageAt > 20_000) {
        dispatch({ type: 'mark_stale' });
      }
    }, 5_000);

    return () => {
      window.clearInterval(staleCheckTimer);
    };
  }, [state.connected, state.lastMessageAt]);

  return { ...state, refresh };
}
