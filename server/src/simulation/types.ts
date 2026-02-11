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
  phase: "setup" | "registration" | "checkin" | "pregame" | "active" | "ended" | "aborted";
  playerCount: number;
  aliveCount: number;
  killCount: number;
  elapsedSeconds: number;
  speedMultiplier: number;
}

export type PlayerAgentState = "wandering" | "hunting" | "fleeing_zone";

export type ItemId = "ping_target" | "ping_hunter";

export interface ItemDefinition {
  id: ItemId;
  name: string;
  cooldownTicks: number;       // ticks before item can be used again (1 tick ≈ 1s)
  durationTicks: number;       // how long the ping circle is visible
  radiusMeters: number;        // radius of the ping circle on the map
  probabilityPerTick: number;  // base probability, multiplied by aggressiveness
}

// Configurable defaults
const PING_COOLDOWN_TICKS = 300;  // 5 minutes
const PING_DURATION_TICKS = 30;   // 30 seconds visible
const PING_RADIUS_METERS = 50;    // 50m circle

export const ITEM_DEFINITIONS: ItemDefinition[] = [
  { id: "ping_target", name: "Ping Target", cooldownTicks: PING_COOLDOWN_TICKS, durationTicks: PING_DURATION_TICKS, radiusMeters: PING_RADIUS_METERS, probabilityPerTick: 0.008 },
  { id: "ping_hunter", name: "Ping Hunter", cooldownTicks: PING_COOLDOWN_TICKS, durationTicks: PING_DURATION_TICKS, radiusMeters: PING_RADIUS_METERS, probabilityPerTick: 0.006 },
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
