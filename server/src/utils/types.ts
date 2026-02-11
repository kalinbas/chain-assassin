// ============ Blockchain Types ============

export enum GamePhase {
  REGISTRATION = 0,
  ACTIVE = 1,
  ENDED = 2,
  CANCELLED = 3,
}

export interface GameConfig {
  gameId: number;
  title: string;
  entryFee: bigint;
  minPlayers: number;
  maxPlayers: number;
  registrationDeadline: number; // unix seconds
  expiryDeadline: number;
  createdAt: number;
  creator: string;
  centerLat: number; // int32 (÷1e6 = degrees)
  centerLng: number;
  meetingLat: number; // int32 (÷1e6 = degrees)
  meetingLng: number;
  bps1st: number;
  bps2nd: number;
  bps3rd: number;
  bpsKills: number;
  bpsPlatform: number;
}

export interface GameState {
  phase: GamePhase;
  playerCount: number;
  totalCollected: bigint;
  winner1: string;
  winner2: string;
  winner3: string;
  topKiller: string;
}

export interface ZoneShrink {
  atSecond: number;
  radiusMeters: number;
}

// ============ Server-Side Game Types ============

export interface ActiveGame {
  config: GameConfig;
  shrinks: ZoneShrink[];
  phase: GamePhase;
  playerCount: number;
  totalCollected: bigint;
  startedAt: number | null;
  tickInterval: ReturnType<typeof setInterval> | null;
}

export interface Player {
  address: string;
  gameId: number;
  playerNumber: number;
  isAlive: boolean;
  kills: number;
  checkedIn: boolean;
  eliminatedAt: number | null;
  eliminatedBy: string | null;
  lastHeartbeatAt: number | null;
}

export interface TargetAssignment {
  hunterAddress: string;
  targetAddress: string;
  assignedAt: number;
}

export interface Kill {
  id?: number;
  gameId: number;
  hunterAddress: string;
  targetAddress: string;
  timestamp: number;
  hunterLat: number | null;
  hunterLng: number | null;
  targetLat: number | null;
  targetLng: number | null;
  distanceMeters: number | null;
  txHash: string | null;
}

export interface LocationPing {
  gameId: number;
  address: string;
  lat: number;
  lng: number;
  timestamp: number;
  isInZone: boolean;
}

export interface OperatorTx {
  id?: number;
  gameId: number;
  action: string;
  txHash: string | null;
  status: "pending" | "submitted" | "confirmed" | "failed";
  createdAt: number;
  confirmedAt: number | null;
  error: string | null;
  params: string | null;
}

// ============ WebSocket Message Types ============

export interface WsAuthMessage {
  type: "auth";
  gameId: number;
  address: string;
  signature: string;
  message: string;
}

export interface WsLocationMessage {
  type: "location";
  lat: number;
  lng: number;
  timestamp: number;
}

export interface WsBleProximityMessage {
  type: "ble_proximity";
  nearbyAddresses: string[];
}

export interface WsSpectateMessage {
  type: "spectate";
  gameId: number;
}

export interface WsHeartbeatScanMessage {
  type: "heartbeat_scan";
  qrPayload: string;
  lat: number;
  lng: number;
  bleNearbyAddresses?: string[];
}

export type WsClientMessage =
  | WsAuthMessage
  | WsLocationMessage
  | WsBleProximityMessage
  | WsSpectateMessage
  | WsHeartbeatScanMessage;

export interface WsServerMessage {
  type: string;
  [key: string]: unknown;
}

// ============ API Types ============

export interface KillSubmission {
  qrPayload: string; // "ca:{gameId}:{playerNumber}"
  hunterLat: number;
  hunterLng: number;
  bleNearbyAddresses: string[];
}

export interface LocationSubmission {
  lat: number;
  lng: number;
  timestamp: number;
}

export interface CheckinSubmission {
  lat: number;
  lng: number;
}

export interface HeartbeatSubmission {
  qrPayload: string;
  lat: number;
  lng: number;
  bleNearbyAddresses?: string[];
}

// ============ Leaderboard Types ============

export interface LeaderboardEntry {
  address: string;
  playerNumber: number;
  kills: number;
  isAlive: boolean;
  eliminatedAt: number | null;
}

// ============ Zone Types ============

export interface ZoneState {
  centerLat: number; // degrees
  centerLng: number; // degrees
  currentRadiusMeters: number;
  nextShrinkAt: number | null; // unix seconds
  nextRadiusMeters: number | null;
}

export interface OutOfZoneTracker {
  address: string;
  exitedAt: number; // unix seconds
  warned: boolean;
}

// ============ Photo Types ============

export interface GamePhoto {
  id: number;
  gameId: number;
  address: string;
  filename: string;
  caption: string | null;
  timestamp: number;
}

// ============ Auth Types ============

export interface AuthenticatedPlayer {
  address: string;
  gameId: number;
}
