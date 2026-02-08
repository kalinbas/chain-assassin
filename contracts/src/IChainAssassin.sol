// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface IChainAssassin {
    // ============ Enums ============

    enum GamePhase {
        REGISTRATION, // 0 — accepting players
        ACTIVE,       // 1 — game in progress
        ENDED,        // 2 — game finished, prizes available
        CANCELLED     // 3 — game cancelled, refunds available
    }

    // ============ Structs ============

    struct GameConfig {
        string  title;
        uint128 entryFee;
        uint16  minPlayers;
        uint16  maxPlayers;
        uint40  registrationDeadline;
        uint40  expiryDeadline;
        uint40  createdAt;
        address creator;
        int32   centerLat;
        int32   centerLng;
        uint16  bps1st;
        uint16  bps2nd;
        uint16  bps3rd;
        uint16  bpsKills;
        uint16  bpsPlatform;
    }

    struct GameState {
        GamePhase phase;
        uint16   playerCount;
        uint128  totalCollected;
        address  winner1;
        address  winner2;
        address  winner3;
        address  topKiller;
    }

    struct ZoneShrink {
        uint32 atSecond;
        uint32 radiusMeters;
    }

    struct CreateGameParams {
        string  title;
        uint128 entryFee;
        uint16  minPlayers;
        uint16  maxPlayers;
        uint40  registrationDeadline;
        uint40  expiryDeadline;
        int32   centerLat;
        int32   centerLng;
        uint16  bps1st;
        uint16  bps2nd;
        uint16  bps3rd;
        uint16  bpsKills;
        uint16  bpsPlatform;
    }

    // ============ Events ============

    event GameCreated(
        uint256 indexed gameId,
        string  title,
        uint128 entryFee,
        uint16  minPlayers,
        uint16  maxPlayers,
        int32   centerLat,
        int32   centerLng
    );

    event PlayerRegistered(
        uint256 indexed gameId,
        address indexed player,
        uint16  playerCount
    );

    event GameStarted(
        uint256 indexed gameId,
        uint16  playerCount
    );

    event PlayerEliminated(
        uint256 indexed gameId,
        address indexed player,
        address indexed eliminator
    );

    event KillRecorded(
        uint256 indexed gameId,
        address indexed hunter,
        address indexed target
    );

    event GameEnded(
        uint256 indexed gameId,
        address winner1,
        address winner2,
        address winner3,
        address topKiller
    );

    event GameCancelled(uint256 indexed gameId);

    event PrizeClaimed(
        uint256 indexed gameId,
        address indexed player,
        uint256 amount
    );

    event RefundClaimed(
        uint256 indexed gameId,
        address indexed player,
        uint256 amount
    );

    event PlatformFeesWithdrawn(address indexed to, uint256 amount);
    event OperatorAdded(address indexed operator);
    event OperatorRemoved(address indexed operator);
}
