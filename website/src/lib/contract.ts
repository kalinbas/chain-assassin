import { JsonRpcProvider, Contract, formatEther } from 'ethers';
import { CONTRACT_ADDRESS, CONTRACT_ABI, RPC_URL, PHASE_NAMES } from '../config/constants';
import type { Game, GameEvent, ActivityType } from '../types/game';

let _provider: JsonRpcProvider | null = null;
let _contract: Contract | null = null;

function getProvider(): JsonRpcProvider {
  if (!_provider) {
    _provider = new JsonRpcProvider(RPC_URL);
  }
  return _provider;
}

function getContract(): Contract {
  if (!_contract) {
    _contract = new Contract(CONTRACT_ADDRESS, CONTRACT_ABI, getProvider());
  }
  return _contract;
}

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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function formatGameData(gameId: number, config: any, state: any, shrinks: any[]): Game {
  const entryFeeWei = config.entryFee;
  const entryFeeEth = parseFloat(formatEther(entryFeeWei));
  const playerCount = Number(state.playerCount);
  const maxPlayers = Number(config.maxPlayers);
  const minPlayers = Number(config.minPlayers);

  const bps1st = Number(config.bps1st);
  const bps2nd = Number(config.bps2nd);
  const bps3rd = Number(config.bps3rd);
  const bpsKills = Number(config.bpsKills);
  const bpsCreator = Number(config.bpsCreator);
  const bpsPlatform = 10000 - bps1st - bps2nd - bps3rd - bpsKills - bpsCreator;

  const gameDate = Number(config.gameDate);
  const maxDuration = Number(config.maxDuration);

  return {
    id: gameId,
    title: config.title,
    entryFee: entryFeeEth,
    entryFeeWei: entryFeeWei,
    location: `${(Number(config.centerLat) / 1e6).toFixed(4)}\u00B0, ${(Number(config.centerLng) / 1e6).toFixed(4)}\u00B0`,
    date: formatDate(gameDate),
    players: playerCount,
    maxPlayers,
    minPlayers,
    registrationDeadline: formatDate(Number(config.registrationDeadline)),
    expiryDeadline: formatDate(gameDate + maxDuration),
    centerLat: Number(config.centerLat) / 1e6,
    centerLng: Number(config.centerLng) / 1e6,
    bps: {
      first: bps1st,
      second: bps2nd,
      third: bps3rd,
      kills: bpsKills,
      creator: bpsCreator,
      platform: bpsPlatform,
    },
    zoneShrinks: shrinks.map((s) => ({
      atSecond: Number(s.atSecond),
      radiusMeters: Number(s.radiusMeters),
    })),
    phase: (PHASE_NAMES[Number(state.phase)] || 'registration') as Game['phase'],
    creator: config.creator,
    createdAt: Number(config.createdAt),
    winner1: state.winner1,
    winner2: state.winner2,
    winner3: state.winner3,
    topKiller: state.topKiller,
    activity: [],
  };
}

export async function loadGame(gameId: number): Promise<Game | null> {
  const contract = getContract();
  const [config, state, shrinks] = await Promise.all([
    contract.getGameConfig(gameId),
    contract.getGameState(gameId),
    contract.getZoneShrinks(gameId),
  ]);

  if (!config.title || config.title === '') return null;

  return formatGameData(gameId, config, state, shrinks);
}

export async function loadAllGames(): Promise<Game[]> {
  const contract = getContract();
  const nextId = Number(await contract.nextGameId());

  if (nextId <= 1) return [];

  const promises: Promise<Game | null>[] = [];
  for (let i = 1; i < nextId; i++) {
    promises.push(loadGame(i));
  }

  const games = await Promise.all(promises);
  return games.filter((g): g is Game => g !== null);
}

export async function loadGameEvents(gameId: number): Promise<GameEvent[]> {
  const contract = getContract();
  const provider = getProvider();
  const activity: GameEvent[] = [];

  try {
    const [createdEvents, registerEvents, startedEvents, endedEvents, cancelledEvents, killEvents] = await Promise.all([
      contract.queryFilter(contract.filters.GameCreated(gameId)),
      contract.queryFilter(contract.filters.PlayerRegistered(gameId)),
      contract.queryFilter(contract.filters.GameStarted(gameId)),
      contract.queryFilter(contract.filters.GameEnded(gameId)),
      contract.queryFilter(contract.filters.GameCancelled(gameId)),
      contract.queryFilter(contract.filters.KillRecorded(gameId)),
    ]);

    const allEvents = [...createdEvents, ...registerEvents, ...startedEvents, ...endedEvents, ...cancelledEvents, ...killEvents];
    const blockNumbers = [...new Set(allEvents.map((e) => e.blockNumber))];
    const blockMap: Record<number, number> = {};
    const blockPromises = blockNumbers.map(async (bn) => {
      const block = await provider.getBlock(bn);
      if (block) blockMap[bn] = block.timestamp;
    });
    await Promise.all(blockPromises);

    for (const ev of createdEvents) {
      activity.push({
        date: formatShortDate(blockMap[ev.blockNumber]),
        text: 'Game created',
        type: 'create' as ActivityType,
        txHash: ev.transactionHash,
        timestamp: blockMap[ev.blockNumber],
      });
    }

    for (const ev of registerEvents) {
      const player = (ev as unknown as { args: string[] }).args[1];
      activity.push({
        date: formatShortDate(blockMap[ev.blockNumber]),
        text: `${truncAddr(player)} registered`,
        type: 'register' as ActivityType,
        txHash: ev.transactionHash,
        timestamp: blockMap[ev.blockNumber],
      });
    }

    for (const ev of startedEvents) {
      const playerCount = (ev as unknown as { args: [unknown, number] }).args[1];
      activity.push({
        date: formatShortDate(blockMap[ev.blockNumber]),
        text: `Game started with ${Number(playerCount)} players`,
        type: 'start' as ActivityType,
        txHash: ev.transactionHash,
        timestamp: blockMap[ev.blockNumber],
      });
    }

    for (const ev of killEvents) {
      const args = (ev as unknown as { args: string[] }).args;
      const hunter = args[1];
      const target = args[2];
      activity.push({
        date: formatShortDate(blockMap[ev.blockNumber]),
        text: `${truncAddr(hunter)} eliminated ${truncAddr(target)}`,
        type: 'kill' as ActivityType,
        txHash: ev.transactionHash,
        timestamp: blockMap[ev.blockNumber],
      });
    }

    for (const ev of endedEvents) {
      activity.push({
        date: formatShortDate(blockMap[ev.blockNumber]),
        text: 'Game ended',
        type: 'end' as ActivityType,
        txHash: ev.transactionHash,
        timestamp: blockMap[ev.blockNumber],
      });
    }

    for (const ev of cancelledEvents) {
      activity.push({
        date: formatShortDate(blockMap[ev.blockNumber]),
        text: 'Game cancelled',
        type: 'cancel' as ActivityType,
        txHash: ev.transactionHash,
        timestamp: blockMap[ev.blockNumber],
      });
    }

    activity.sort((a, b) => b.timestamp - a.timestamp);
  } catch (err) {
    console.warn('Failed to load game events:', err);
  }

  return activity;
}
