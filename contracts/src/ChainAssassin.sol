// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "@openzeppelin/contracts/access/Ownable.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import "./IChainAssassin.sol";

/// @title ChainAssassin — On-chain game contract for real-world "Assassin" hunts
/// @author Chain Assassin team
/// @notice Manages game lifecycle: creation → registration → active play → end/cancel.
///         Funds are held in escrow and distributed as prizes or refunds.
/// @dev Trust model: operators (set by owner) are trusted to faithfully report kills
///      and winners from the off-chain game server.  The contract enforces financial
///      integrity (correct prize math, refunds, reentrancy safety) but does NOT verify
///      kill proofs on-chain — that is the server's responsibility.
///
///      Accepted risk — no emergency cancel for ACTIVE games:
///      There is intentionally no operator-callable cancel for ACTIVE games.  If the
///      off-chain server goes down during a game, funds remain in escrow until the game
///      expires (`gameDate + maxDuration`), at which point anyone can call `triggerExpiry()`
///      to move the game to CANCELLED and unlock full refunds for all players.
///      This avoids giving operators the power to cancel games at will (potential griefing)
///      while still guaranteeing funds are never permanently locked.
contract ChainAssassin is IChainAssassin, Ownable, ReentrancyGuard {
    // ============ Constants ============

    /// @notice BPS denominator — all bps* fields must sum to this value.
    uint256 public constant BPS_TOTAL = 10_000;

    /// @notice Maximum allowed platform fee in BPS (50% = 5000).
    uint16 public constant MAX_PLATFORM_FEE_BPS = 5000;

    /// @notice Hard cap on maxPlayers per game.
    /// @dev Keeps killCount (uint16, max 65 535) safe from overflow since a player
    ///      can record at most MAX_PLAYERS − 1 kills in a single game.
    uint16 public constant MAX_PLAYERS = 9999;

    /// @notice Maximum allowed title length in bytes.
    uint256 public constant MAX_TITLE_LENGTH = 256;

    // ============ Storage ============

    /// @notice Monotonically increasing game ID counter. First game is ID 1.
    uint256 public nextGameId = 1;

    /// @dev gameId → immutable game configuration.
    mapping(uint256 => GameConfig) internal _gameConfigs;
    /// @dev gameId → mutable game state.
    mapping(uint256 => GameState) internal _gameStates;
    /// @dev gameId → zone-shrink schedule (ordered by atSecond, ascending).
    mapping(uint256 => ZoneShrink[]) internal _zoneShrinks;

    /// @notice gameId → player → has registered.
    mapping(uint256 => mapping(address => bool)) public isRegistered;
    /// @notice gameId → player → has claimed prize or refund.
    mapping(uint256 => mapping(address => bool)) public hasClaimed;
    /// @notice gameId → player → still alive.
    mapping(uint256 => mapping(address => bool)) public isAlive;
    /// @notice gameId → player → number of kills.
    /// @dev uint16 is safe because MAX_PLAYERS (9 999) bounds the maximum possible kills.
    mapping(uint256 => mapping(address => uint16)) public killCount;
    /// @notice gameId → player → 1-based registration number (order of registration).
    mapping(uint256 => mapping(address => uint16)) public playerNumber;

    /// @notice Accumulated platform fees across all games, withdrawable by owner.
    uint256 public platformFeesAccrued;

    /// @notice Accumulated creator fees per address, withdrawable by each creator.
    mapping(address => uint256) public creatorFeesAccrued;

    /// @notice Global platform fee rate in BPS, set by owner. Applied to every game.
    uint16 public platformFeeBps;

    /// @notice address → is approved operator.
    mapping(address => bool) public isOperator;

    // ============ Modifiers ============

    /// @dev Reverts with `NotOperator` unless caller is an operator or the owner.
    modifier onlyOperator() {
        if (!isOperator[msg.sender] && msg.sender != owner()) revert NotOperator();
        _;
    }

    /// @dev Reverts with `WrongPhase` unless the game is in the expected phase.
    modifier inPhase(uint256 gameId, GamePhase phase) {
        if (_gameStates[gameId].phase != phase) revert WrongPhase();
        _;
    }

    // ============ Constructor ============

    /// @param _platformFeeBps Initial global platform fee in BPS.
    constructor(uint16 _platformFeeBps) Ownable(msg.sender) {
        if (_platformFeeBps > MAX_PLATFORM_FEE_BPS) revert PlatformFeeTooHigh();
        platformFeeBps = _platformFeeBps;
    }

    // ============ Admin Functions ============

    /// @notice Grant operator privileges to `op`.
    /// @param op Address to add as operator. Must not be address(0).
    function addOperator(address op) external onlyOwner {
        if (op == address(0)) revert ZeroAddress();
        isOperator[op] = true;
        emit OperatorAdded(op);
    }

    /// @notice Revoke operator privileges from `op`.
    /// @dev No-op if `op` is not currently an operator.
    /// @param op Address to remove.
    function removeOperator(address op) external onlyOwner {
        if (!isOperator[op]) return;
        isOperator[op] = false;
        emit OperatorRemoved(op);
    }

    /// @notice Update the global platform fee rate.
    /// @param newBps New platform fee in BPS. Must be ≤ MAX_PLATFORM_FEE_BPS.
    /// @dev Only affects games created after this call.
    function setPlatformFeeBps(uint16 newBps) external onlyOwner {
        if (newBps > MAX_PLATFORM_FEE_BPS) revert PlatformFeeTooHigh();
        uint16 oldBps = platformFeeBps;
        platformFeeBps = newBps;
        emit PlatformFeeBpsUpdated(oldBps, newBps);
    }

    /// @notice Withdraw all accumulated platform fees to `to`.
    /// @param to Recipient address. Must not be address(0).
    function withdrawPlatformFees(address to) external onlyOwner nonReentrant {
        if (to == address(0)) revert ZeroAddress();
        uint256 amount = platformFeesAccrued;
        if (amount == 0) revert NoFees();
        platformFeesAccrued = 0;
        (bool success,) = to.call{value: amount}("");
        if (!success) revert TransferFailed();
        emit PlatformFeesWithdrawn(to, amount);
    }

    // ============ Operator Functions ============

    /// @notice Create a new game with the given parameters and zone-shrink schedule.
    /// @param params Game configuration (see CreateGameParams).
    /// @param shrinks Zone-shrink schedule; must be non-empty with strictly increasing `atSecond`.
    /// @return gameId The newly assigned game ID (starts at 1, monotonically increases).
    function createGame(
        CreateGameParams calldata params,
        ZoneShrink[] calldata shrinks
    ) external payable onlyOperator returns (uint256 gameId) {
        // --- Title ---
        if (bytes(params.title).length > MAX_TITLE_LENGTH) revert TitleTooLong();

        // --- Player count sanity (an assassin game needs at least 2 players) ---
        if (params.minPlayers < 2) revert MinPlayersTooLow();
        if (params.maxPlayers < 2) revert MaxPlayersTooLow();
        if (params.maxPlayers > MAX_PLAYERS) revert MaxPlayersTooHigh();
        if (params.maxPlayers < params.minPlayers) revert MaxLessThanMin();

        // --- Deadlines ---
        if (params.registrationDeadline <= block.timestamp) revert DeadlineInPast();
        if (params.gameDate <= params.registrationDeadline) revert GameDateNotAfterDeadline();
        if (params.maxDuration == 0) revert MaxDurationZero();

        // --- Prize distribution ---
        // Game-level BPS (prizes + creator) + global platform fee must total 10 000.
        if (uint256(params.bps1st) + params.bps2nd + params.bps3rd + params.bpsKills + params.bpsCreator + platformFeeBps != BPS_TOTAL) {
            revert BpsSumNot10000();
        }
        if (params.bps1st == 0) revert NeedFirstPrize();
        if (params.bps3rd > 0 && params.bps2nd == 0) revert Need2ndIf3rdSet();

        // Derive minimum required distinct winners from BPS config
        uint16 requiredWinners = 1;
        if (params.bps2nd > 0) requiredWinners = 2;
        if (params.bps3rd > 0) requiredWinners = 3;
        if (params.minPlayers < requiredWinners) revert MinPlayersLessThanPrizeSlots();

        // --- Zone shrink schedule ---
        if (shrinks.length == 0) revert NoShrinkSchedule();
        if (shrinks[0].atSecond != 0) revert FirstShrinkMustBeZero();
        for (uint256 i = 1; i < shrinks.length; i++) {
            if (shrinks[i].atSecond <= shrinks[i - 1].atSecond) revert ShrinksNotOrdered();
            if (shrinks[i].radiusMeters >= shrinks[i - 1].radiusMeters) revert RadiiNotDecreasing();
        }

        // --- Persist ---
        gameId = nextGameId++;

        _gameConfigs[gameId] = GameConfig({
            title: params.title,
            entryFee: params.entryFee,
            minPlayers: params.minPlayers,
            maxPlayers: params.maxPlayers,
            registrationDeadline: params.registrationDeadline,
            gameDate: params.gameDate,
            maxDuration: params.maxDuration,
            createdAt: uint40(block.timestamp),
            creator: msg.sender,
            centerLat: params.centerLat,
            centerLng: params.centerLng,
            meetingLat: params.meetingLat,
            meetingLng: params.meetingLng,
            bps1st: params.bps1st,
            bps2nd: params.bps2nd,
            bps3rd: params.bps3rd,
            bpsKills: params.bpsKills,
            bpsCreator: params.bpsCreator,
            baseReward: uint128(msg.value)
        });

        // Seed totalCollected with base reward so BPS math includes it
        if (msg.value > 0) {
            _gameStates[gameId].totalCollected = uint128(msg.value);
        }

        for (uint256 i = 0; i < shrinks.length; i++) {
            _zoneShrinks[gameId].push(shrinks[i]);
        }

        emit GameCreated(
            gameId, params.title, params.entryFee,
            uint128(msg.value),
            params.minPlayers, params.maxPlayers,
            params.centerLat, params.centerLng
        );
    }

    /// @notice Transition a game from REGISTRATION to ACTIVE.
    /// @dev Cannot be called before `gameDate`. The registration deadline only gates new
    ///      registrations, not the operator's ability to start.
    /// @param gameId The game to start.
    function startGame(uint256 gameId) external onlyOperator inPhase(gameId, GamePhase.REGISTRATION) {
        GameState storage state = _gameStates[gameId];
        GameConfig storage config = _gameConfigs[gameId];
        if (block.timestamp < config.gameDate) revert GameDateNotReached();
        if (state.playerCount < config.minPlayers) revert NotEnoughPlayers();

        state.phase = GamePhase.ACTIVE;
        emit GameStarted(gameId, state.playerCount);
    }

    /// @notice Record a kill: mark target as eliminated and increment hunter's kill count.
    /// @dev Called by the off-chain server after verifying QR-scan proof.
    /// @param gameId The active game.
    /// @param hunter The player who made the kill.
    /// @param target The player who was eliminated.
    function recordKill(
        uint256 gameId,
        address hunter,
        address target
    ) external onlyOperator inPhase(gameId, GamePhase.ACTIVE) {
        if (!isRegistered[gameId][hunter]) revert HunterNotRegistered();
        if (!isRegistered[gameId][target]) revert TargetNotRegistered();
        if (!isAlive[gameId][hunter]) revert HunterNotAlive();
        if (!isAlive[gameId][target]) revert TargetNotAlive();
        if (hunter == target) revert CannotSelfKill();

        killCount[gameId][hunter]++;
        isAlive[gameId][target] = false;

        emit KillRecorded(gameId, hunter, target);
        emit PlayerEliminated(gameId, target, hunter);
    }

    /// @notice Eliminate a player without crediting any hunter (zone death, disconnect, etc.).
    /// @param gameId The active game.
    /// @param player The player to eliminate.
    function eliminatePlayer(
        uint256 gameId,
        address player
    ) external onlyOperator inPhase(gameId, GamePhase.ACTIVE) {
        if (!isRegistered[gameId][player]) revert PlayerNotRegistered();
        if (!isAlive[gameId][player]) revert PlayerNotAlive();

        isAlive[gameId][player] = false;

        emit PlayerEliminated(gameId, player, address(0));
    }

    /// @notice End a game and record the final standings.
    /// @dev A player may hold multiple roles (e.g. winner1 + topKiller) and will receive
    ///      the sum of all matching BPS shares in a single `claimPrize` call.
    ///      Winner addresses must be distinct from each other when their BPS > 0.
    ///      topKiller is allowed to overlap with any winner.
    ///      Winners are only required to be registered, not alive — final standings are
    ///      determined by the off-chain server which may use custom scoring rules.
    /// @param gameId  The active game.
    /// @param winner1  1st place (required).
    /// @param winner2  2nd place (required if bps2nd > 0, must be address(0) otherwise).
    /// @param winner3  3rd place (required if bps3rd > 0, must be address(0) otherwise).
    /// @param topKiller  Top killer (required if bpsKills > 0, must be address(0) otherwise).
    function endGame(
        uint256 gameId,
        address winner1,
        address winner2,
        address winner3,
        address topKiller
    ) external onlyOperator inPhase(gameId, GamePhase.ACTIVE) {
        GameConfig storage config = _gameConfigs[gameId];

        // --- Validate winners ---
        if (winner1 == address(0)) revert WinnerZeroAddress();
        if (!isRegistered[gameId][winner1]) revert WinnerNotRegistered();

        if (config.bps2nd > 0) {
            if (winner2 == address(0)) revert WinnerZeroAddress();
            if (!isRegistered[gameId][winner2]) revert WinnerNotRegistered();
            if (winner2 == winner1) revert WinnersNotUnique();
        } else {
            if (winner2 != address(0)) revert UnusedWinnerNotZero();
        }
        if (config.bps3rd > 0) {
            if (winner3 == address(0)) revert WinnerZeroAddress();
            if (!isRegistered[gameId][winner3]) revert WinnerNotRegistered();
            if (winner3 == winner1 || winner3 == winner2) revert WinnersNotUnique();
        } else {
            if (winner3 != address(0)) revert UnusedWinnerNotZero();
        }
        if (config.bpsKills > 0) {
            if (topKiller == address(0)) revert TopKillerZeroAddress();
            if (!isRegistered[gameId][topKiller]) revert TopKillerNotRegistered();
        } else {
            if (topKiller != address(0)) revert UnusedTopKillerNotZero();
        }

        // --- Update state ---
        GameState storage state = _gameStates[gameId];

        state.phase = GamePhase.ENDED;
        state.winner1 = winner1;
        state.winner2 = winner2;
        state.winner3 = winner3;
        state.topKiller = topKiller;

        // --- Accrue fees ---
        // Compute each prize share exactly as getClaimableAmount() does, then derive
        // the platform fee as the remainder so that no dust is ever locked in the contract.
        uint256 total = state.totalCollected;

        uint256 prizePool = total * config.bps1st / BPS_TOTAL
                          + total * config.bps2nd / BPS_TOTAL
                          + total * config.bps3rd / BPS_TOTAL
                          + total * config.bpsKills / BPS_TOTAL;

        uint256 creatorFee = config.bpsCreator > 0
            ? total * config.bpsCreator / BPS_TOTAL
            : 0;

        if (creatorFee > 0) {
            creatorFeesAccrued[config.creator] += creatorFee;
        }

        uint256 platformFee = total - prizePool - creatorFee;
        if (platformFee > 0) {
            platformFeesAccrued += platformFee;
        }

        emit GameEnded(gameId, winner1, winner2, winner3, topKiller);
    }

    // ============ Public Functions ============

    /// @notice Register the caller for a game by paying the exact entry fee.
    /// @param gameId The game in REGISTRATION phase.
    function register(uint256 gameId) external payable inPhase(gameId, GamePhase.REGISTRATION) {
        GameConfig storage config = _gameConfigs[gameId];
        GameState storage state = _gameStates[gameId];

        if (block.timestamp > config.registrationDeadline) revert RegistrationClosed();
        if (msg.value != config.entryFee) revert WrongEntryFee();
        if (state.playerCount >= config.maxPlayers) revert GameFull();
        if (isRegistered[gameId][msg.sender]) revert AlreadyRegistered();

        isRegistered[gameId][msg.sender] = true;
        isAlive[gameId][msg.sender] = true;
        state.playerCount++;
        playerNumber[gameId][msg.sender] = state.playerCount;
        // Safe cast: msg.value == config.entryFee which is uint128, so no truncation.
        state.totalCollected += uint128(msg.value);

        emit PlayerRegistered(gameId, msg.sender, state.playerCount);
    }

    /// @notice Cancel a game that failed to reach minPlayers by the registration deadline.
    /// @dev Anyone can call this — it's a permissionless trigger, not an admin action.
    /// @param gameId The game in REGISTRATION phase.
    function triggerCancellation(uint256 gameId) external inPhase(gameId, GamePhase.REGISTRATION) {
        GameConfig storage config = _gameConfigs[gameId];
        GameState storage state = _gameStates[gameId];

        if (block.timestamp <= config.registrationDeadline) revert DeadlineNotPassed();
        if (state.playerCount >= config.minPlayers) revert EnoughPlayers();

        state.phase = GamePhase.CANCELLED;

        // Return base reward to creator via creatorFeesAccrued
        if (config.baseReward > 0) {
            creatorFeesAccrued[config.creator] += config.baseReward;
        }

        emit GameCancelled(gameId);
    }

    /// @notice Cancel a game that has exceeded its maximum duration without ending.
    /// @dev Anyone can call this. Expiry is computed as `gameDate + maxDuration`.
    ///      Works for both ACTIVE games (server died) and REGISTRATION games that
    ///      met minPlayers but were never started by the operator.
    ///      After cancellation, players may claim full refunds.
    ///      No platform fees are accrued since endGame was never called.
    /// @param gameId The game in REGISTRATION or ACTIVE phase.
    function triggerExpiry(uint256 gameId) external {
        GamePhase phase = _gameStates[gameId].phase;
        if (phase != GamePhase.ACTIVE && phase != GamePhase.REGISTRATION) revert WrongPhase();

        GameConfig storage config = _gameConfigs[gameId];

        if (block.timestamp <= uint256(config.gameDate) + config.maxDuration) revert NotExpiredYet();

        _gameStates[gameId].phase = GamePhase.CANCELLED;

        // Return base reward to creator via creatorFeesAccrued
        if (config.baseReward > 0) {
            creatorFeesAccrued[config.creator] += config.baseReward;
        }

        emit GameCancelled(gameId);
    }

    /// @notice Claim prize winnings for the caller in an ended game.
    /// @dev The claimable amount is the sum of all matching BPS shares (winner1/2/3 + topKiller).
    ///      Reentrancy-safe via OpenZeppelin ReentrancyGuard.
    /// @param gameId The game in ENDED phase.
    function claimPrize(uint256 gameId) external nonReentrant inPhase(gameId, GamePhase.ENDED) {
        if (hasClaimed[gameId][msg.sender]) revert AlreadyClaimed();

        uint256 amount = getClaimableAmount(gameId, msg.sender);
        if (amount == 0) revert NoPrize();

        hasClaimed[gameId][msg.sender] = true;

        (bool success,) = msg.sender.call{value: amount}("");
        if (!success) revert TransferFailed();

        emit PrizeClaimed(gameId, msg.sender, amount);
    }

    /// @notice Claim a full refund for the caller in a cancelled game.
    /// @dev Returns exactly `config.entryFee` per player.  No platform fees are deducted
    ///      because platform fees are only accrued in `endGame()`.
    ///      Reentrancy-safe via OpenZeppelin ReentrancyGuard.
    /// @param gameId The game in CANCELLED phase.
    function claimRefund(uint256 gameId) external nonReentrant inPhase(gameId, GamePhase.CANCELLED) {
        if (!isRegistered[gameId][msg.sender]) revert PlayerNotRegistered();
        if (hasClaimed[gameId][msg.sender]) revert AlreadyClaimed();

        GameConfig storage config = _gameConfigs[gameId];
        uint256 amount = config.entryFee;

        hasClaimed[gameId][msg.sender] = true;

        (bool success,) = msg.sender.call{value: amount}("");
        if (!success) revert TransferFailed();

        emit RefundClaimed(gameId, msg.sender, amount);
    }

    /// @notice Withdraw all accumulated creator fees for the caller.
    /// @param to Recipient address. Must not be address(0).
    function withdrawCreatorFees(address to) external nonReentrant {
        if (to == address(0)) revert ZeroAddress();
        uint256 amount = creatorFeesAccrued[msg.sender];
        if (amount == 0) revert NoFees();
        creatorFeesAccrued[msg.sender] = 0;
        (bool success,) = to.call{value: amount}("");
        if (!success) revert TransferFailed();
        emit CreatorFeesWithdrawn(msg.sender, to, amount);
    }

    // ============ View Functions ============

    /// @notice Return the immutable configuration for a game.
    function getGameConfig(uint256 gameId) external view returns (GameConfig memory) {
        return _gameConfigs[gameId];
    }

    /// @notice Return the mutable state for a game.
    function getGameState(uint256 gameId) external view returns (GameState memory) {
        return _gameStates[gameId];
    }

    /// @notice Return the zone-shrink schedule for a game.
    function getZoneShrinks(uint256 gameId) external view returns (ZoneShrink[] memory) {
        return _zoneShrinks[gameId];
    }

    /// @notice Return registration status, alive status, kill count, claim status, and player number.
    function getPlayerInfo(uint256 gameId, address player)
        external
        view
        returns (bool registered, bool alive, uint16 kills, bool claimed, uint16 number)
    {
        return (
            isRegistered[gameId][player],
            isAlive[gameId][player],
            killCount[gameId][player],
            hasClaimed[gameId][player],
            playerNumber[gameId][player]
        );
    }

    /// @notice Calculate the claimable prize amount for `player` in game `gameId`.
    /// @dev Returns 0 if game is not ENDED, player has already claimed, or player
    ///      holds no winning position.  A player may hold multiple roles and will
    ///      receive the sum of all matching BPS shares.
    function getClaimableAmount(uint256 gameId, address player) public view returns (uint256) {
        GameState storage state = _gameStates[gameId];
        if (state.phase != GamePhase.ENDED) return 0;
        if (hasClaimed[gameId][player]) return 0;

        GameConfig storage config = _gameConfigs[gameId];
        uint256 total = state.totalCollected;
        uint256 amount = 0;

        if (player == state.winner1) amount += total * config.bps1st / BPS_TOTAL;
        if (player == state.winner2) amount += total * config.bps2nd / BPS_TOTAL;
        if (player == state.winner3) amount += total * config.bps3rd / BPS_TOTAL;
        if (player == state.topKiller) amount += total * config.bpsKills / BPS_TOTAL;

        return amount;
    }
}
