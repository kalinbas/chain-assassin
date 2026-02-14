export type Phase = 'registration' | 'active' | 'ended' | 'cancelled';
export type SubPhase = 'checkin' | 'pregame' | 'game';

export type ActivityType = 'create' | 'register' | 'start' | 'kill' | 'end' | 'cancel';

export interface GameBps {
  first: number;
  second: number;
  third: number;
  kills: number;
  creator: number;
  platform: number;
}

export interface ZoneShrink {
  atSecond: number;
  radiusMeters: number;
}

export interface GameEvent {
  date: string;
  text: string;
  type: ActivityType;
  txHash: string;
  timestamp: number;
}

export interface GameLeaderboardEntry {
  playerNumber: number;
  address: string;
  kills: number;
  isAlive: boolean;
  eliminatedAt: number | null;
}

export interface Game {
  id: number;
  title: string;
  gameDateTs: number;
  entryFee: number;
  entryFeeWei: bigint;
  baseReward: number;
  baseRewardWei: bigint;
  location: string;
  date: string;
  players: number;
  maxPlayers: number;
  minPlayers: number;
  registrationDeadline: string;
  registrationDeadlineTs: number;
  expiryDeadline: string;
  centerLat: number;
  centerLng: number;
  meetingLat: number;
  meetingLng: number;
  bps: GameBps;
  zoneShrinks: ZoneShrink[];
  phase: Phase;
  subPhase?: SubPhase;
  checkinEndsAt?: number | null;
  pregameEndsAt?: number | null;
  playerCount?: number;
  aliveCount?: number;
  checkedInCount?: number;
  creator: string;
  createdAt: number;
  winner1: number;  // playerNumber (0 = none)
  winner2: number;
  winner3: number;
  topKiller: number;
  activity: GameEvent[];
  leaderboard: GameLeaderboardEntry[];
}
