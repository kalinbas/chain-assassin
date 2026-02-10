// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title IChainAssassin — Interface for the Chain-Assassin on-chain game
/// @notice Defines the types, errors, and events used by ChainAssassin.sol
/// @dev All BPS (basis-point) fields use a base of 10 000 (100%).
interface IChainAssassin {
    // ============ Enums ============

    /// @notice Lifecycle phases of a game.
    enum GamePhase {
        REGISTRATION, // 0 — accepting player registrations
        ACTIVE,       // 1 — game in progress (kills allowed)
        ENDED,        // 2 — game finished, prize claims open
        CANCELLED     // 3 — game cancelled, refund claims open
    }

    // ============ Structs ============

    /// @notice Immutable configuration written once at game creation.
    /// @dev Packed into two storage slots by the compiler.
    struct GameConfig {
        string  title;                  // human-readable game name
        uint128 entryFee;               // wei per player
        uint16  minPlayers;             // minimum to start
        uint16  maxPlayers;             // hard cap on registrations
        uint40  registrationDeadline;   // unix timestamp; registration closes after this
        uint40  gameDate;               // unix timestamp; when the game starts (must be > registrationDeadline)
        uint32  maxDuration;            // seconds; max game length; expiry = gameDate + maxDuration
        uint40  createdAt;              // unix timestamp; set to block.timestamp on creation
        address creator;                // operator that created the game
        int32   centerLat;              // centre latitude  (× 1e6, e.g. 19435244 → 19.435244°)
        int32   centerLng;              // centre longitude (× 1e6)
        uint16  bps1st;                 // 1st-place share  (e.g. 4000 = 40%)
        uint16  bps2nd;                 // 2nd-place share
        uint16  bps3rd;                 // 3rd-place share
        uint16  bpsKills;               // top-killer share
        uint16  bpsCreator;             // game creator fee share
    }

    /// @notice Mutable runtime state of a game.
    struct GameState {
        GamePhase phase;
        uint16   playerCount;       // current registered player count
        uint128  totalCollected;    // cumulative entry fees in wei
        address  winner1;           // 1st place (set in endGame)
        address  winner2;           // 2nd place
        address  winner3;           // 3rd place
        address  topKiller;         // most kills
    }

    /// @notice A single entry in the zone-shrink schedule.
    /// @param atSecond  Seconds since game start when this shrink takes effect.
    /// @param radiusMeters  New zone radius at that time.
    struct ZoneShrink {
        uint32 atSecond;
        uint32 radiusMeters;
    }

    /// @notice Parameters for `createGame()`. Mirrors GameConfig minus the auto-set fields.
    struct CreateGameParams {
        string  title;
        uint128 entryFee;
        uint16  minPlayers;
        uint16  maxPlayers;
        uint40  registrationDeadline;
        uint40  gameDate;
        uint32  maxDuration;
        int32   centerLat;
        int32   centerLng;
        uint16  bps1st;
        uint16  bps2nd;
        uint16  bps3rd;
        uint16  bpsKills;
        uint16  bpsCreator;
    }

    // ============ Errors ============

    // --- Access ---
    /// @dev Caller is not an operator or the owner.
    error NotOperator();

    // --- Phase ---
    /// @dev The game is not in the required phase.
    error WrongPhase();

    // --- General ---
    /// @dev A zero-address was passed where a real address is required.
    error ZeroAddress();
    /// @dev An ETH transfer via `call` failed.
    error TransferFailed();

    // --- createGame ---
    /// @dev maxPlayers < minPlayers.
    error MaxLessThanMin();
    /// @dev minPlayers < 2.
    error MinPlayersTooLow();
    /// @dev maxPlayers < 2.
    error MaxPlayersTooLow();
    /// @dev maxPlayers > MAX_PLAYERS.
    error MaxPlayersTooHigh();
    /// @dev registrationDeadline is in the past.
    error DeadlineInPast();
    /// @dev gameDate ≤ registrationDeadline.
    error GameDateNotAfterDeadline();
    /// @dev maxDuration is zero.
    error MaxDurationZero();
    /// @dev BPS fields do not sum to 10 000.
    error BpsSumNot10000();
    /// @dev No zone-shrink schedule provided.
    error NoShrinkSchedule();
    /// @dev bps1st must be > 0.
    error NeedFirstPrize();
    /// @dev bps3rd > 0 but bps2nd == 0 (skip not allowed).
    error Need2ndIf3rdSet();
    /// @dev minPlayers < number of non-zero prize tiers.
    error MinPlayersLessThanPrizeSlots();
    /// @dev Zone shrink times are not strictly increasing.
    error ShrinksNotOrdered();
    /// @dev First shrink entry must start at second 0.
    error FirstShrinkMustBeZero();
    /// @dev Zone radii must strictly decrease with each shrink step.
    error RadiiNotDecreasing();
    /// @dev Title exceeds MAX_TITLE_LENGTH bytes.
    error TitleTooLong();

    // --- startGame ---
    /// @dev playerCount < minPlayers.
    error NotEnoughPlayers();
    /// @dev block.timestamp < gameDate.
    error GameDateNotReached();

    // --- recordKill ---
    /// @dev Hunter address is not registered for this game.
    error HunterNotRegistered();
    /// @dev Target address is not registered for this game.
    error TargetNotRegistered();
    /// @dev Hunter is already eliminated.
    error HunterNotAlive();
    /// @dev Target is already eliminated.
    error TargetNotAlive();
    /// @dev hunter == target.
    error CannotSelfKill();

    // --- eliminatePlayer ---
    /// @dev Player is not registered for this game.
    error PlayerNotRegistered();
    /// @dev Player is already eliminated.
    error PlayerNotAlive();

    // --- endGame ---
    /// @dev A required winner address is zero.
    error WinnerZeroAddress();
    /// @dev A winner is not registered for this game.
    error WinnerNotRegistered();
    /// @dev Two or more winner slots share the same address.
    error WinnersNotUnique();
    /// @dev topKiller is zero when bpsKills > 0.
    error TopKillerZeroAddress();
    /// @dev topKiller is not registered for this game.
    error TopKillerNotRegistered();
    /// @dev An unused winner slot (bps == 0) was passed a non-zero address.
    error UnusedWinnerNotZero();
    /// @dev topKiller was passed a non-zero address when bpsKills == 0.
    error UnusedTopKillerNotZero();

    // --- register ---
    /// @dev Registration deadline has passed.
    error RegistrationClosed();
    /// @dev msg.value ≠ entryFee.
    error WrongEntryFee();
    /// @dev playerCount == maxPlayers.
    error GameFull();
    /// @dev Caller is already registered.
    error AlreadyRegistered();

    // --- triggerCancellation ---
    /// @dev Registration deadline has not passed yet.
    error DeadlineNotPassed();
    /// @dev playerCount ≥ minPlayers so cancellation is not warranted.
    error EnoughPlayers();

    // --- triggerExpiry ---
    /// @dev Game has not expired yet (block.timestamp ≤ gameDate + maxDuration).
    error NotExpiredYet();

    // --- claimPrize / claimRefund ---
    /// @dev Caller has already claimed for this game.
    error AlreadyClaimed();
    /// @dev Caller has no prize to claim.
    error NoPrize();

    // --- withdrawPlatformFees / withdrawCreatorFees ---
    /// @dev Fee balance is zero.
    error NoFees();

    // --- setPlatformFeeBps ---
    /// @dev Platform fee BPS exceeds maximum allowed.
    error PlatformFeeTooHigh();

    // ============ Events ============

    /// @notice Emitted when a new game is created.
    /// @dev The platform fee applicable to this game is not stored explicitly; it can be
    ///      derived as `BPS_TOTAL - (bps1st + bps2nd + bps3rd + bpsKills + bpsCreator)`.
    ///      Off-chain indexers should read the game config at creation time to reconstruct it.
    event GameCreated(
        uint256 indexed gameId,
        string  title,
        uint128 entryFee,
        uint16  minPlayers,
        uint16  maxPlayers,
        int32   centerLat,
        int32   centerLng
    );

    /// @notice Emitted when a player registers for a game.
    event PlayerRegistered(
        uint256 indexed gameId,
        address indexed player,
        uint16  playerCount
    );

    /// @notice Emitted when a game transitions to ACTIVE.
    event GameStarted(
        uint256 indexed gameId,
        uint16  playerCount
    );

    /// @notice Emitted when a player is eliminated (by a kill or zone / admin action).
    /// @param eliminator The hunter, or `address(0)` for admin/zone eliminations.
    event PlayerEliminated(
        uint256 indexed gameId,
        address indexed player,
        address indexed eliminator
    );

    /// @notice Emitted alongside PlayerEliminated for hunter kills; carries the kill relationship.
    event KillRecorded(
        uint256 indexed gameId,
        address indexed hunter,
        address indexed target
    );

    /// @notice Emitted when a game transitions to ENDED.
    event GameEnded(
        uint256 indexed gameId,
        address winner1,
        address winner2,
        address winner3,
        address topKiller
    );

    /// @notice Emitted when a game is cancelled (deadline or expiry).
    event GameCancelled(uint256 indexed gameId);

    /// @notice Emitted when a winner claims their prize.
    event PrizeClaimed(
        uint256 indexed gameId,
        address indexed player,
        uint256 amount
    );

    /// @notice Emitted when a player claims their refund after cancellation.
    event RefundClaimed(
        uint256 indexed gameId,
        address indexed player,
        uint256 amount
    );

    /// @notice Emitted when the owner withdraws accumulated platform fees.
    event PlatformFeesWithdrawn(address indexed to, uint256 amount);

    /// @notice Emitted when a game creator withdraws accumulated creator fees.
    event CreatorFeesWithdrawn(address indexed creator, address indexed to, uint256 amount);

    /// @notice Emitted when the owner changes the global platform fee rate.
    event PlatformFeeBpsUpdated(uint16 oldBps, uint16 newBps);

    /// @notice Emitted when an operator is added.
    event OperatorAdded(address indexed operator);

    /// @notice Emitted when an operator is removed.
    event OperatorRemoved(address indexed operator);
}
