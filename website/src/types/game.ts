export type Phase = 'registration' | 'active' | 'ended' | 'cancelled';

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

export interface Game {
  id: number;
  title: string;
  entryFee: number;
  entryFeeWei: bigint;
  location: string;
  date: string;
  players: number;
  maxPlayers: number;
  minPlayers: number;
  registrationDeadline: string;
  expiryDeadline: string;
  centerLat: number;
  centerLng: number;
  bps: GameBps;
  zoneShrinks: ZoneShrink[];
  phase: Phase;
  creator: string;
  createdAt: number;
  winner1: string;
  winner2: string;
  winner3: string;
  topKiller: string;
  activity: GameEvent[];
}
