export const CONTRACT_ADDRESS = '0xA9AC5fe70646b7a24Cc7BFeDe2A367B7bF2015b2';
export const RPC_URL = 'https://base-sepolia.g.alchemy.com/v2/gwRYWylWRij2jXTnPXR90v-YqXh96PDX';
export const EXPLORER_URL = 'https://sepolia.basescan.org';

export const CONTRACT_ABI = [
  'function nextGameId() view returns (uint256)',
  'function platformFeeBps() view returns (uint16)',
  'function getGameConfig(uint256 gameId) view returns (tuple(string title, uint128 entryFee, uint16 minPlayers, uint16 maxPlayers, uint40 registrationDeadline, uint40 gameDate, uint32 maxDuration, uint40 createdAt, address creator, int32 centerLat, int32 centerLng, int32 meetingLat, int32 meetingLng, uint16 bps1st, uint16 bps2nd, uint16 bps3rd, uint16 bpsKills, uint16 bpsCreator, uint128 baseReward))',
  'function getGameState(uint256 gameId) view returns (tuple(uint8 phase, uint16 playerCount, uint128 totalCollected, address winner1, address winner2, address winner3, address topKiller))',
  'function getZoneShrinks(uint256 gameId) view returns (tuple(uint32 atSecond, uint32 radiusMeters)[])',
  'event GameCreated(uint256 indexed gameId, string title, uint128 entryFee, uint128 baseReward, uint16 minPlayers, uint16 maxPlayers, int32 centerLat, int32 centerLng)',
  'event PlayerRegistered(uint256 indexed gameId, address indexed player, uint16 playerCount)',
  'event GameStarted(uint256 indexed gameId, uint16 playerCount)',
  'event GameEnded(uint256 indexed gameId, address winner1, address winner2, address winner3, address topKiller)',
  'event GameCancelled(uint256 indexed gameId)',
  'event KillRecorded(uint256 indexed gameId, address indexed hunter, address indexed target)',
  'event PlayerEliminated(uint256 indexed gameId, address indexed player, address indexed eliminator)',
] as const;

export const PHASE_NAMES: readonly string[] = ['registration', 'active', 'ended', 'cancelled'];
