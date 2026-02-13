// ============ Blockchain Types ============

export enum GamePhase {
  REGISTRATION = 0,
  ACTIVE = 1,
  ENDED = 2,
  CANCELLED = 3,
}

export type ActiveSubPhase = 'checkin' | 'pregame' | 'game';

export interface GameConfig {
  gameId: number;
  title: string;
  entryFee: bigint;
  minPlayers: number;
  maxPlayers: number;
  registrationDeadline: number; // unix seconds
  gameDate: number; // unix seconds — when check-in opens and startGame is allowed
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
  bpsCreator: number;
  baseReward: bigint;
  maxDuration: number;
}

export interface GameState {
  phase: GamePhase;
  playerCount: number;
  totalCollected: bigint;
  winner1: number;  // playerNumber (0 = none)
  winner2: number;
  winner3: number;
  topKiller: number;
}

export interface PlayerStateOnChain {
  addr: string;
  alive: boolean;
  claimed: boolean;
  killCount: number;
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
  bluetoothId: string | null;
  eliminatedAt: number | null;
  eliminatedBy: string | null;
  lastHeartbeatAt: number | null;
  hasClaimed: boolean;
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
  | WsSpectateMessage
  | WsHeartbeatScanMessage;

export interface WsServerMessage {
  type: string;
  [key: string]: unknown;
}

// ============ API Types ============

export interface KillSubmission {
  qrPayload: string; // obfuscated numeric payload (see utils/crypto.ts)
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
  qrPayload?: string;
  bluetoothId?: string;
  bleNearbyAddresses?: string[];
}

export interface HeartbeatSubmission {
  qrPayload: string;
  lat: number;
  lng: number;
  bleNearbyAddresses?: string[];
}

// ============ Leaderboard Types ============

export interface LeaderboardEntry {
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
