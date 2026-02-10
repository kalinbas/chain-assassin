import { useEffect, useReducer, useRef, useCallback } from 'react';
import { SERVER_WS_URL } from '../config/server';
import { truncAddr } from '../lib/contract';

export interface SpectatorPlayer {
  address: string;
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

export interface SpectatorState {
  connected: boolean;
  phase: string | null;
  zone: {
    centerLat: number;
    centerLng: number;
    currentRadiusMeters: number;
    nextShrinkAt?: number | null;
    nextRadiusMeters?: number | null;
  } | null;
  players: SpectatorPlayer[];
  leaderboard: { address: string; playerNumber: number; kills: number; isAlive: boolean }[];
  events: SpectatorEvent[];
  aliveCount: number;
  playerCount: number;
  winners: { winner1: string; winner2: string; winner3: string; topKiller: string } | null;
  killFlashes: KillFlash[];
  trails: Record<string, { lat: number; lng: number; timestamp: number }[]>;
  huntLinks: { hunter: string; target: string }[];
  ghostedPlayers: Record<string, number>; // address → expiresAt timestamp
  totalKills: number;
  gameStartedAt: number | null;
}

const initialState: SpectatorState = {
  connected: false,
  phase: null,
  zone: null,
  players: [],
  leaderboard: [],
  events: [],
  aliveCount: 0,
  playerCount: 0,
  winners: null,
  killFlashes: [],
  trails: {},
  huntLinks: [],
  ghostedPlayers: {},
  totalKills: 0,
  gameStartedAt: null,
};

type Action =
  | { type: 'connected' }
  | { type: 'disconnected' }
  | { type: 'init'; payload: Record<string, unknown> }
  | { type: 'positions'; payload: Record<string, unknown> }
  | { type: 'kill'; payload: Record<string, unknown> }
  | { type: 'eliminated'; payload: Record<string, unknown> }
  | { type: 'zone_shrink'; payload: Record<string, unknown> }
  | { type: 'leaderboard'; payload: Record<string, unknown> }
  | { type: 'game_started'; payload: Record<string, unknown> }
  | { type: 'game_ended'; payload: Record<string, unknown> }
  | { type: 'item_used'; payload: Record<string, unknown> };

function addEvent(events: SpectatorEvent[], text: string, type: string, meta?: SpectatorEvent['meta']): SpectatorEvent[] {
  const updated = [{ type, text, timestamp: Date.now(), meta }, ...events];
  return updated.slice(0, 50);
}

function reducer(state: SpectatorState, action: Action): SpectatorState {
  switch (action.type) {
    case 'connected':
      return { ...state, connected: true };

    case 'disconnected':
      return { ...state, connected: false };

    case 'init': {
      const p = action.payload;
      return {
        ...state,
        connected: true,
        phase: p.phase as string,
        playerCount: p.playerCount as number,
        aliveCount: p.aliveCount as number,
        leaderboard: p.leaderboard as SpectatorState['leaderboard'],
        zone: p.zone as SpectatorState['zone'],
        players: p.players as SpectatorPlayer[],
        winners: (p.winner1 && p.winner1 !== '0x0000000000000000000000000000000000000000')
          ? { winner1: p.winner1 as string, winner2: p.winner2 as string, winner3: p.winner3 as string, topKiller: p.topKiller as string }
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
          const existing = newTrails[player.address] || [];
          const updated = [...existing, { lat: player.lat, lng: player.lng, timestamp: now }];
          newTrails[player.address] = updated.slice(-8);
        }
      }

      // Clean up old kill flashes (>3s)
      const killFlashes = state.killFlashes.filter((f) => now - f.timestamp < 3000);

      // Clean up expired ghost effects
      const ghostedPlayers: Record<string, number> = {};
      for (const [addr, expiresAt] of Object.entries(state.ghostedPlayers)) {
        if (expiresAt > now) ghostedPlayers[addr] = expiresAt;
      }

      return {
        ...state,
        players: newPlayers,
        zone: p.zone as SpectatorState['zone'],
        aliveCount: p.aliveCount as number,
        huntLinks: (p.huntLinks as SpectatorState['huntLinks']) ?? state.huntLinks,
        trails: newTrails,
        killFlashes,
        ghostedPlayers,
      };
    }

    case 'kill': {
      const p = action.payload;
      const hunter = truncAddr(p.hunter as string);
      const target = truncAddr(p.target as string);
      const targetAddr = p.target as string;

      // Find target's last position for kill flash
      const targetPlayer = state.players.find((pl) => pl.address === targetAddr);
      const newFlashes = [...state.killFlashes];
      if (targetPlayer?.lat != null && targetPlayer?.lng != null) {
        newFlashes.push({ lat: targetPlayer.lat, lng: targetPlayer.lng, timestamp: Date.now() });
      }

      return {
        ...state,
        events: addEvent(state.events, `${hunter} eliminated ${target}`, 'kill'),
        killFlashes: newFlashes,
        totalKills: state.totalKills + 1,
      };
    }

    case 'eliminated': {
      const p = action.payload;
      const player = truncAddr(p.player as string);
      const reason = p.reason as string;
      if (reason === 'zone_violation') {
        return {
          ...state,
          events: addEvent(state.events, `${player} eliminated by zone`, 'zone_elim'),
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

    case 'game_started': {
      const p = action.payload;
      return {
        ...state,
        phase: 'active',
        playerCount: p.playerCount as number,
        events: addEvent(state.events, 'Game started!', 'start'),
        gameStartedAt: Date.now(),
      };
    }

    case 'game_ended': {
      const p = action.payload;
      return {
        ...state,
        phase: 'ended',
        winners: {
          winner1: p.winner1 as string,
          winner2: p.winner2 as string,
          winner3: p.winner3 as string,
          topKiller: p.topKiller as string,
        },
        events: addEvent(state.events, `Game ended! Winner: ${truncAddr(p.winner1 as string)}`, 'end'),
      };
    }

    case 'item_used': {
      const p = action.payload;
      const player = truncAddr(p.playerAddress as string);
      const itemName = p.itemName as string;
      const itemId = p.itemId as string;

      // Track ghost mode effect
      const ghostedPlayers = { ...state.ghostedPlayers };
      if (itemId === 'ghost_mode') {
        ghostedPlayers[p.playerAddress as string] = Date.now() + 120_000; // 2 minutes
      }

      return {
        ...state,
        events: addEvent(state.events, `${player} used ${itemName}`, 'item', { itemId }),
        ghostedPlayers,
      };
    }

    default:
      return state;
  }
}

export function useSpectatorSocket(gameId: number): SpectatorState {
  const [state, dispatch] = useReducer(reducer, initialState);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelayRef = useRef(1000);

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;

    const ws = new WebSocket(`${SERVER_WS_URL}/ws`);
    wsRef.current = ws;

    ws.onopen = () => {
      reconnectDelayRef.current = 1000;
      ws.send(JSON.stringify({ type: 'spectate', gameId }));
      dispatch({ type: 'connected' });
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        switch (msg.type) {
          case 'spectate:init':
            dispatch({ type: 'init', payload: msg });
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
          case 'game:started_broadcast':
            dispatch({ type: 'game_started', payload: msg });
            break;
          case 'game:ended':
            dispatch({ type: 'game_ended', payload: msg });
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
      dispatch({ type: 'disconnected' });
      const delay = Math.min(reconnectDelayRef.current, 30000);
      reconnectTimeoutRef.current = setTimeout(() => {
        reconnectDelayRef.current *= 2;
        connect();
      }, delay);
    };

    ws.onerror = () => {
      ws.close();
    };
  }, [gameId]);

  useEffect(() => {
    connect();

    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [connect]);

  return state;
}
