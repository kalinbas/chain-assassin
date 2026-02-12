import { SERVER_URL } from '../config/server';
import { PHASE_NAMES } from '../config/constants';
import type { Game, GameEvent, ActivityType } from '../types/game';

function formatDate(timestamp: number): string {
  const d = new Date(timestamp * 1000);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const month = months[d.getMonth()];
  const day = d.getDate();
  const hours = String(d.getHours()).padStart(2, '0');
  const mins = String(d.getMinutes()).padStart(2, '0');
  return `${month} ${day} · ${hours}:${mins}`;
}

function formatShortDate(timestamp: number): string {
  const d = new Date(timestamp * 1000);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${months[d.getMonth()]} ${d.getDate()}, ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function truncAddr(addr: string): string {
  return addr.slice(0, 6) + '...' + addr.slice(-4);
}

// Wei string → ETH number
function weiToEth(wei: string): number {
  const bi = BigInt(wei);
  // Integer part + fractional: divide by 1e18
  const whole = bi / 1000000000000000000n;
  const frac = bi % 1000000000000000000n;
  return Number(whole) + Number(frac) / 1e18;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function formatServerGame(data: any): Game {
  const entryFeeWei = BigInt(data.entryFee);
  const baseRewardWei = BigInt(data.baseReward);

  const bps1st = Number(data.bps1st);
  const bps2nd = Number(data.bps2nd);
  const bps3rd = Number(data.bps3rd);
  const bpsKills = Number(data.bpsKills);
  const bpsCreator = Number(data.bpsCreator);
  const bpsPlatform = 10000 - bps1st - bps2nd - bps3rd - bpsKills - bpsCreator;

  const gameDate = Number(data.gameDate);
  const maxDuration = Number(data.maxDuration);

  return {
    id: data.gameId,
    title: data.title,
    entryFee: weiToEth(data.entryFee),
    entryFeeWei,
    baseReward: weiToEth(data.baseReward),
    baseRewardWei,
    location: `${(Number(data.centerLat) / 1e6).toFixed(4)}\u00B0, ${(Number(data.centerLng) / 1e6).toFixed(4)}\u00B0`,
    date: formatDate(gameDate),
    players: Number(data.playerCount),
    maxPlayers: Number(data.maxPlayers),
    minPlayers: Number(data.minPlayers),
    registrationDeadline: formatDate(Number(data.registrationDeadline)),
    registrationDeadlineTs: Number(data.registrationDeadline),
    expiryDeadline: formatDate(gameDate + maxDuration),
    centerLat: Number(data.centerLat) / 1e6,
    centerLng: Number(data.centerLng) / 1e6,
    meetingLat: Number(data.meetingLat) / 1e6,
    meetingLng: Number(data.meetingLng) / 1e6,
    bps: {
      first: bps1st,
      second: bps2nd,
      third: bps3rd,
      kills: bpsKills,
      creator: bpsCreator,
      platform: bpsPlatform,
    },
    zoneShrinks: (data.zoneShrinks || []).map((s: { atSecond: number; radiusMeters: number }) => ({
      atSecond: Number(s.atSecond),
      radiusMeters: Number(s.radiusMeters),
    })),
    phase: (PHASE_NAMES[Number(data.phase)] || 'registration') as Game['phase'],
    subPhase: data.subPhase ?? undefined,
    checkinEndsAt: data.checkinEndsAt ?? undefined,
    pregameEndsAt: data.pregameEndsAt ?? undefined,
    playerCount: data.aliveCount != null ? Number(data.playerCount) : undefined,
    aliveCount: data.aliveCount != null ? Number(data.aliveCount) : undefined,
    creator: data.creator,
    createdAt: Number(data.createdAt),
    winner1: Number(data.winner1),
    winner2: Number(data.winner2),
    winner3: Number(data.winner3),
    topKiller: Number(data.topKiller),
    activity: [],
  };
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function formatActivity(ev: any): GameEvent {
  return {
    date: formatShortDate(ev.timestamp),
    text: ev.text,
    type: ev.type as ActivityType,
    txHash: ev.txHash ?? '',
    timestamp: ev.timestamp,
  };
}

export async function loadAllGames(): Promise<Game[]> {
  const res = await fetch(`${SERVER_URL}/api/games`);
  if (!res.ok) throw new Error('Failed to load games');
  const data = await res.json();
  return data.map(formatServerGame);
}

export async function loadGame(gameId: number): Promise<Game | null> {
  const res = await fetch(`${SERVER_URL}/api/games/${gameId}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error('Failed to load game');
  const data = await res.json();
  const game = formatServerGame(data);
  game.activity = (data.activity || []).map(formatActivity);
  return game;
}
