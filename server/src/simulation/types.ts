import type { ZoneShrink } from "../utils/types.js";

export interface SimulationConfig {
  playerCount: number;
  centerLat: number;
  centerLng: number;
  initialRadiusMeters: number;
  speedMultiplier: number;
  useOnChain: boolean;
  title: string;
  entryFeeWei: string;
}

export interface SimulationStatus {
  gameId: number;
  title: string;
  phase: "setup" | "registration" | "checkin" | "active" | "ended" | "aborted";
  playerCount: number;
  aliveCount: number;
  killCount: number;
  elapsedSeconds: number;
  speedMultiplier: number;
}

export type PlayerAgentState = "wandering" | "hunting" | "fleeing_zone";

export type ItemId = "ping_target" | "ping_hunter" | "ghost_mode" | "decoy_ping" | "emp_blast";

export interface ItemDefinition {
  id: ItemId;
  name: string;
  cooldownTicks: number;
  probabilityPerTick: number; // base probability, multiplied by aggressiveness
}

export const ITEM_DEFINITIONS: ItemDefinition[] = [
  { id: "ping_target", name: "Ping Target", cooldownTicks: 60, probabilityPerTick: 0.008 },
  { id: "ping_hunter", name: "Ping Hunter", cooldownTicks: 60, probabilityPerTick: 0.006 },
  { id: "ghost_mode", name: "Ghost Mode", cooldownTicks: 180, probabilityPerTick: 0.004 },
  { id: "decoy_ping", name: "Decoy Ping", cooldownTicks: 45, probabilityPerTick: 0.010 },
  { id: "emp_blast", name: "EMP Blast", cooldownTicks: 90, probabilityPerTick: 0.005 },
];

export interface SimulatedPlayer {
  address: string;
  playerNumber: number;
  lat: number;
  lng: number;
  isAlive: boolean;
  aggressiveness: number; // 0.5-2.0, multiplier for pursuit speed and kill probability
  state: PlayerAgentState;
  itemCooldowns: Record<ItemId, number>; // remaining ticks until item is available
}

export const DEFAULT_ZONE_SHRINKS: ZoneShrink[] = [
  { atSecond: 0, radiusMeters: 500 },
  { atSecond: 300, radiusMeters: 350 },
  { atSecond: 600, radiusMeters: 200 },
  { atSecond: 900, radiusMeters: 100 },
  { atSecond: 1200, radiusMeters: 50 },
];
