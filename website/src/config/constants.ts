export const CONTRACT_ADDRESS = '0x0ABfD376Bd339A6dcd885F37aB0A9cE761c2F99e';
export const RPC_URL = 'https://sepolia.base.org';
export const EXPLORER_URL = 'https://sepolia.basescan.org';

export const CONTRACT_ABI = [
  'function nextGameId() view returns (uint256)',
  'function platformFeeBps() view returns (uint16)',
  'function getGameConfig(uint256 gameId) view returns (tuple(string title, uint128 entryFee, uint16 minPlayers, uint16 maxPlayers, uint40 registrationDeadline, uint40 gameDate, uint32 maxDuration, uint40 createdAt, address creator, int32 centerLat, int32 centerLng, int32 meetingLat, int32 meetingLng, uint16 bps1st, uint16 bps2nd, uint16 bps3rd, uint16 bpsKills, uint16 bpsCreator))',
  'function getGameState(uint256 gameId) view returns (tuple(uint8 phase, uint16 playerCount, uint128 totalCollected, address winner1, address winner2, address winner3, address topKiller))',
  'function getZoneShrinks(uint256 gameId) view returns (tuple(uint32 atSecond, uint32 radiusMeters)[])',
  'event GameCreated(uint256 indexed gameId, string title, uint128 entryFee, uint16 minPlayers, uint16 maxPlayers, int32 centerLat, int32 centerLng)',
  'event PlayerRegistered(uint256 indexed gameId, address indexed player, uint16 playerCount)',
  'event GameStarted(uint256 indexed gameId, uint16 playerCount)',
  'event GameEnded(uint256 indexed gameId, address winner1, address winner2, address winner3, address topKiller)',
  'event GameCancelled(uint256 indexed gameId)',
  'event KillRecorded(uint256 indexed gameId, address indexed hunter, address indexed target)',
  'event PlayerEliminated(uint256 indexed gameId, address indexed player, address indexed eliminator)',
] as const;

export const PHASE_NAMES: readonly string[] = ['registration', 'active', 'ended', 'cancelled'];
