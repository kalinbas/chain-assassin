// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "@openzeppelin/contracts/access/Ownable.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import "./IChainAssassin.sol";

contract ChainAssassin is IChainAssassin, Ownable, ReentrancyGuard {
    // ============ Constants ============

    uint256 public constant BPS_TOTAL = 10000;

    // ============ Storage ============

    uint256 public nextGameId;

    mapping(uint256 => GameConfig) internal _gameConfigs;
    mapping(uint256 => GameState) internal _gameStates;
    mapping(uint256 => ZoneShrink[]) internal _zoneShrinks;

    mapping(uint256 => mapping(address => bool)) public isRegistered;
    mapping(uint256 => mapping(address => bool)) public hasClaimed;
    mapping(uint256 => mapping(address => bool)) public isAlive;
    mapping(uint256 => mapping(address => uint16)) public killCount;

    uint256 public platformFeesAccrued;

    mapping(address => bool) public isOperator;

    // ============ Modifiers ============

    modifier onlyOperator() {
        require(isOperator[msg.sender] || msg.sender == owner(), "Not operator");
        _;
    }

    modifier inPhase(uint256 gameId, GamePhase phase) {
        require(_gameStates[gameId].phase == phase, "Wrong phase");
        _;
    }

    // ============ Constructor ============

    constructor() Ownable(msg.sender) {}

    // ============ Admin Functions ============

    function addOperator(address op) external onlyOwner {
        require(op != address(0), "Zero address");
        isOperator[op] = true;
        emit OperatorAdded(op);
    }

    function removeOperator(address op) external onlyOwner {
        isOperator[op] = false;
        emit OperatorRemoved(op);
    }

    function withdrawPlatformFees(address to) external onlyOwner nonReentrant {
        require(to != address(0), "Zero address");
        uint256 amount = platformFeesAccrued;
        require(amount > 0, "No fees");
        platformFeesAccrued = 0;
        (bool success,) = to.call{value: amount}("");
        require(success, "Transfer failed");
        emit PlatformFeesWithdrawn(to, amount);
    }

    // ============ Operator Functions ============

    function createGame(
        CreateGameParams calldata params,
        ZoneShrink[] calldata shrinks
    ) external onlyOperator returns (uint256 gameId) {
        require(params.maxPlayers >= params.minPlayers, "Max < min");
        require(params.registrationDeadline > block.timestamp, "Deadline in past");
        require(params.expiryDeadline > params.registrationDeadline, "Expiry before deadline");
        require(
            uint256(params.bps1st) + params.bps2nd + params.bps3rd + params.bpsKills + params.bpsPlatform == BPS_TOTAL,
            "BPS must sum to 10000"
        );
        require(shrinks.length > 0, "Need shrink schedule");

        // Validate prize tier hierarchy: higher tiers require lower tiers
        require(params.bps1st > 0, "Need 1st place prize");
        if (params.bps3rd > 0) require(params.bps2nd > 0, "Need 2nd if 3rd set");

        // Derive minimum required winners from BPS config
        uint16 requiredWinners = 1;
        if (params.bps2nd > 0) requiredWinners = 2;
        if (params.bps3rd > 0) requiredWinners = 3;
        require(params.minPlayers >= requiredWinners, "Min players < prize slots");

        // Validate shrink schedule is ordered
        for (uint256 i = 1; i < shrinks.length; i++) {
            require(shrinks[i].atSecond > shrinks[i - 1].atSecond, "Shrinks not ordered");
        }

        gameId = nextGameId++;

        _gameConfigs[gameId] = GameConfig({
            title: params.title,
            entryFee: params.entryFee,
            minPlayers: params.minPlayers,
            maxPlayers: params.maxPlayers,
            registrationDeadline: params.registrationDeadline,
            expiryDeadline: params.expiryDeadline,
            createdAt: uint40(block.timestamp),
            creator: msg.sender,
            centerLat: params.centerLat,
            centerLng: params.centerLng,
            bps1st: params.bps1st,
            bps2nd: params.bps2nd,
            bps3rd: params.bps3rd,
            bpsKills: params.bpsKills,
            bpsPlatform: params.bpsPlatform
        });

        // Store shrink schedule
        for (uint256 i = 0; i < shrinks.length; i++) {
            _zoneShrinks[gameId].push(shrinks[i]);
        }

        emit GameCreated(
            gameId, params.title, params.entryFee,
            params.minPlayers, params.maxPlayers,
            params.centerLat, params.centerLng
        );
    }

    function startGame(uint256 gameId) external onlyOperator inPhase(gameId, GamePhase.REGISTRATION) {
        GameState storage state = _gameStates[gameId];
        GameConfig storage config = _gameConfigs[gameId];
        require(state.playerCount >= config.minPlayers, "Not enough players");

        state.phase = GamePhase.ACTIVE;
        emit GameStarted(gameId, state.playerCount);
    }

    function recordKill(
        uint256 gameId,
        address hunter,
        address target
    ) external onlyOperator inPhase(gameId, GamePhase.ACTIVE) {
        require(isRegistered[gameId][hunter], "Hunter not registered");
        require(isRegistered[gameId][target], "Target not registered");
        require(isAlive[gameId][hunter], "Hunter not alive");
        require(isAlive[gameId][target], "Target not alive");
        require(hunter != target, "Cannot self-kill");

        killCount[gameId][hunter]++;
        isAlive[gameId][target] = false;

        emit KillRecorded(gameId, hunter, target);
        emit PlayerEliminated(gameId, target, hunter);
    }

    function eliminatePlayer(
        uint256 gameId,
        address player
    ) external onlyOperator inPhase(gameId, GamePhase.ACTIVE) {
        require(isRegistered[gameId][player], "Not registered");
        require(isAlive[gameId][player], "Not alive");

        isAlive[gameId][player] = false;

        emit PlayerEliminated(gameId, player, address(0));
    }

    function endGame(
        uint256 gameId,
        address winner1,
        address winner2,
        address winner3,
        address topKiller
    ) external onlyOperator inPhase(gameId, GamePhase.ACTIVE) {
        GameConfig storage config = _gameConfigs[gameId];

        // Winner required when their BPS > 0, address(0) allowed when BPS == 0
        require(winner1 != address(0), "Winner1 zero address");
        require(isRegistered[gameId][winner1], "Winner1 not registered");

        if (config.bps2nd > 0) {
            require(winner2 != address(0), "Winner2 zero address");
            require(isRegistered[gameId][winner2], "Winner2 not registered");
            require(winner2 != winner1, "Winner2 same as winner1");
        }
        if (config.bps3rd > 0) {
            require(winner3 != address(0), "Winner3 zero address");
            require(isRegistered[gameId][winner3], "Winner3 not registered");
            require(winner3 != winner1 && winner3 != winner2, "Winner3 not unique");
        }
        if (config.bpsKills > 0) {
            require(topKiller != address(0), "TopKiller zero address");
            require(isRegistered[gameId][topKiller], "TopKiller not registered");
        }

        GameState storage state = _gameStates[gameId];

        state.phase = GamePhase.ENDED;
        state.winner1 = winner1;
        state.winner2 = winner2;
        state.winner3 = winner3;
        state.topKiller = topKiller;

        // Accrue platform fees
        if (config.bpsPlatform > 0) {
            platformFeesAccrued += state.totalCollected * config.bpsPlatform / BPS_TOTAL;
        }

        emit GameEnded(gameId, winner1, winner2, winner3, topKiller);
    }

    // ============ Public Functions ============

    function register(uint256 gameId) external payable inPhase(gameId, GamePhase.REGISTRATION) {
        GameConfig storage config = _gameConfigs[gameId];
        GameState storage state = _gameStates[gameId];

        require(block.timestamp <= config.registrationDeadline, "Registration closed");
        require(msg.value == config.entryFee, "Wrong entry fee");
        require(state.playerCount < config.maxPlayers, "Game full");
        require(!isRegistered[gameId][msg.sender], "Already registered");

        isRegistered[gameId][msg.sender] = true;
        isAlive[gameId][msg.sender] = true;
        state.playerCount++;
        state.totalCollected += uint128(msg.value);

        emit PlayerRegistered(gameId, msg.sender, state.playerCount);
    }

    function triggerCancellation(uint256 gameId) external inPhase(gameId, GamePhase.REGISTRATION) {
        GameConfig storage config = _gameConfigs[gameId];
        GameState storage state = _gameStates[gameId];

        require(block.timestamp > config.registrationDeadline, "Deadline not passed");
        require(state.playerCount < config.minPlayers, "Enough players");

        state.phase = GamePhase.CANCELLED;
        emit GameCancelled(gameId);
    }

    function triggerExpiry(uint256 gameId) external inPhase(gameId, GamePhase.ACTIVE) {
        GameConfig storage config = _gameConfigs[gameId];

        require(block.timestamp > config.expiryDeadline, "Not expired yet");

        _gameStates[gameId].phase = GamePhase.CANCELLED;
        emit GameCancelled(gameId);
    }

    function claimPrize(uint256 gameId) external nonReentrant inPhase(gameId, GamePhase.ENDED) {
        require(!hasClaimed[gameId][msg.sender], "Already claimed");

        uint256 amount = getClaimableAmount(gameId, msg.sender);
        require(amount > 0, "No prize");

        hasClaimed[gameId][msg.sender] = true;

        (bool success,) = msg.sender.call{value: amount}("");
        require(success, "Transfer failed");

        emit PrizeClaimed(gameId, msg.sender, amount);
    }

    function claimRefund(uint256 gameId) external nonReentrant inPhase(gameId, GamePhase.CANCELLED) {
        require(isRegistered[gameId][msg.sender], "Not registered");
        require(!hasClaimed[gameId][msg.sender], "Already claimed");

        GameConfig storage config = _gameConfigs[gameId];
        uint256 amount = config.entryFee;

        hasClaimed[gameId][msg.sender] = true;

        (bool success,) = msg.sender.call{value: amount}("");
        require(success, "Transfer failed");

        emit RefundClaimed(gameId, msg.sender, amount);
    }

    // ============ View Functions ============

    function getGameConfig(uint256 gameId) external view returns (GameConfig memory) {
        return _gameConfigs[gameId];
    }

    function getGameState(uint256 gameId) external view returns (GameState memory) {
        return _gameStates[gameId];
    }

    function getZoneShrinks(uint256 gameId) external view returns (ZoneShrink[] memory) {
        return _zoneShrinks[gameId];
    }

    function getPlayerInfo(uint256 gameId, address player)
        external
        view
        returns (bool registered, bool alive, uint16 kills, bool claimed)
    {
        return (
            isRegistered[gameId][player],
            isAlive[gameId][player],
            killCount[gameId][player],
            hasClaimed[gameId][player]
        );
    }

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
