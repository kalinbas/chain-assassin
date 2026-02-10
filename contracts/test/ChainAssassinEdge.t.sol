// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./ChainAssassinTestBase.sol";

// Reentrancy attack contract
contract ReentrancyAttacker {
    ChainAssassin public target;
    uint256 public gameId;
    uint8 public attackMode; // 0=off, 1=claimPrize, 2=claimRefund, 3=withdrawCreator, 4=withdrawPlatform

    constructor(ChainAssassin _target) {
        target = _target;
    }

    function setGameId(uint256 _gameId) external {
        gameId = _gameId;
    }

    function attackClaimPrize() external {
        attackMode = 1;
        target.claimPrize(gameId);
    }

    function attackClaimRefund() external {
        attackMode = 2;
        target.claimRefund(gameId);
    }

    function attackWithdrawCreatorFees(address to) external {
        attackMode = 3;
        target.withdrawCreatorFees(to);
    }

    receive() external payable {
        uint8 mode = attackMode;
        if (mode != 0) {
            attackMode = 0;
            // Try to re-enter
            if (mode == 1) { try target.claimPrize(gameId) {} catch {} }
            if (mode == 2) { try target.claimRefund(gameId) {} catch {} }
            if (mode == 3) { try target.withdrawCreatorFees(address(this)) {} catch {} }
        }
    }
}

contract ChainAssassinEdgeTest is ChainAssassinTestBase {
    // ============ Reentrancy Tests ============

    function test_reentrancy_claimPrize() public {
        ReentrancyAttacker attacker = new ReentrancyAttacker(game);
        address attackerAddr = address(attacker);
        vm.deal(attackerAddr, 10 ether);

        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());

        vm.prank(attackerAddr);
        game.register{value: ENTRY_FEE}(gameId);
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, attackerAddr, player1, player2, attackerAddr);

        attacker.setGameId(gameId);
        uint256 balBefore = attackerAddr.balance;

        attacker.attackClaimPrize();

        // Should only receive prize once (35% + 20% since winner1 + topKiller)
        uint256 totalPool = 0.15 ether; // 3 players
        uint256 expected = totalPool * (3500 + 2000) / 10000;
        assertEq(attackerAddr.balance - balBefore, expected);

        // Verify claimed flag is set
        assertTrue(game.hasClaimed(gameId, attackerAddr));
    }

    function test_reentrancy_claimRefund() public {
        ReentrancyAttacker attacker = new ReentrancyAttacker(game);
        address attackerAddr = address(attacker);
        vm.deal(attackerAddr, 10 ether);

        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());

        vm.prank(attackerAddr);
        game.register{value: ENTRY_FEE}(gameId);

        vm.warp(block.timestamp + 2 days);
        game.triggerCancellation(gameId);

        attacker.setGameId(gameId);
        uint256 balBefore = attackerAddr.balance;

        attacker.attackClaimRefund();

        // Should only receive refund once
        assertEq(attackerAddr.balance - balBefore, ENTRY_FEE);
        assertTrue(game.hasClaimed(gameId, attackerAddr));
    }

    // ============ Multiple Games ============

    function test_multipleGamesSimultaneously() public {
        vm.prank(operator);
        uint256 game1 = game.createGame(_defaultParams(), _defaultShrinks());
        vm.prank(operator);
        uint256 game2 = game.createGame(_defaultParams(), _defaultShrinks());

        assertEq(game1, 1);
        assertEq(game2, 2);

        // Register in both
        _registerPlayer(game1, player1);
        _registerPlayer(game1, player2);
        _registerPlayer(game1, player3);
        _registerPlayer(game2, player1);
        _registerPlayer(game2, player2);
        _registerPlayer(game2, player3);

        // Start and end game1
        _startGame(game1);
        vm.prank(operator);
        game.endGame(game1, player1, player2, player3, player1);

        // Game2 still in registration
        IChainAssassin.GameState memory s1 = game.getGameState(game1);
        IChainAssassin.GameState memory s2 = game.getGameState(game2);
        assertEq(uint8(s1.phase), uint8(IChainAssassin.GamePhase.ENDED));
        assertEq(uint8(s2.phase), uint8(IChainAssassin.GamePhase.REGISTRATION));
    }

    // ============ Zero Entry Fee ============

    function test_zeroEntryFeeGame() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.entryFee = 0;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        vm.prank(player1); game.register{value: 0}(gameId);
        vm.prank(player2); game.register{value: 0}(gameId);
        vm.prank(player3); game.register{value: 0}(gameId);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // Prize should be 0
        assertEq(game.getClaimableAmount(gameId, player1), 0);
    }

    // ============ Min == Max Players ============

    function test_minEqualsMaxPlayers() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.minPlayers = 3;
        params.maxPlayers = 3;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        // Can't register a 4th
        vm.prank(player4);
        vm.expectRevert(IChainAssassin.GameFull.selector);
        game.register{value: ENTRY_FEE}(gameId);

        // Can start
        _startGame(gameId);
    }

    // ============ Expiry Tests ============

    function test_triggerExpiry_success() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        _startGame(gameId);

        // Warp past expiry
        vm.warp(block.timestamp + 3 days);

        vm.expectEmit(true, false, false, true);
        emit IChainAssassin.GameCancelled(gameId);
        game.triggerExpiry(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.CANCELLED));

        // Players can claim refunds
        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimRefund(gameId);
        assertEq(player1.balance - balBefore, ENTRY_FEE);
    }

    function test_triggerExpiry_revertsBeforeDeadline() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        _startGame(gameId);

        vm.expectRevert(IChainAssassin.NotExpiredYet.selector);
        game.triggerExpiry(gameId);
    }

    function test_triggerExpiry_revertsIfNotActive() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());

        vm.warp(block.timestamp + 3 days);

        vm.expectRevert(IChainAssassin.WrongPhase.selector);
        game.triggerExpiry(gameId);
    }

    function test_endGameBeforeExpiry_preventsExpiry() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        _startGame(gameId);

        // End game normally
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // Now try to trigger expiry after deadline
        vm.warp(block.timestamp + 3 days);

        vm.expectRevert(IChainAssassin.WrongPhase.selector);
        game.triggerExpiry(gameId);
    }

    // ============ Operator Management ============

    function test_addOperator() public {
        address newOp = address(0x999);
        game.addOperator(newOp);
        assertTrue(game.isOperator(newOp));
    }

    function test_removeOperator() public {
        game.removeOperator(operator);
        assertFalse(game.isOperator(operator));

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.NotOperator.selector);
        game.createGame(_defaultParams(), _defaultShrinks());
    }

    function test_addOperator_revertsZeroAddress() public {
        vm.expectRevert(IChainAssassin.ZeroAddress.selector);
        game.addOperator(address(0));
    }

    function test_removeOperator_nonOperatorIsNoop() public {
        // Removing a non-operator should not revert, just be a no-op (no event emitted)
        vm.recordLogs();
        game.removeOperator(address(0x999));
        Vm.Log[] memory logs = vm.getRecordedLogs();
        assertEq(logs.length, 0); // no OperatorRemoved event
        assertFalse(game.isOperator(address(0x999)));
    }

    function test_withdrawPlatformFees_revertsZeroAddress() public {
        _setupEndedGame();
        vm.expectRevert(IChainAssassin.ZeroAddress.selector);
        game.withdrawPlatformFees(address(0));
    }

    function test_withdrawPlatformFees_revertsWhenNoFees() public {
        vm.expectRevert(IChainAssassin.NoFees.selector);
        game.withdrawPlatformFees(address(0xFEE));
    }

    function test_ownerCanActAsOperator() public {
        // Owner should be able to call operator functions directly
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        assertEq(gameId, 1);
    }

    // ============ 2-Player Game (Only 1st Place Prize) ============

    function test_twoPlayerOnlyFirstPrize_endToEnd() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 8000;
        params.bps2nd = 0;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsCreator = 1000;
        params.minPlayers = 2;
        params.maxPlayers = 10;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);

        _startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, address(0), address(0), address(0));

        // player1 gets 80% of 0.10 ETH (2 players × 0.05)
        uint256 expected = 0.10 ether * 8000 / 10000;
        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimPrize(gameId);
        assertEq(player1.balance - balBefore, expected);
    }

    // ============ 2-Player Game (1st + 2nd Place) ============

    function test_twoPlayerGame_endToEnd() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 5000;
        params.bps2nd = 3000;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsCreator = 1000;
        params.minPlayers = 2;
        params.maxPlayers = 10;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);

        _startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, address(0), address(0));

        uint256 total = 0.1 ether; // 2 * 0.05
        uint256 expected1 = total * 5000 / 10000;
        uint256 expected2 = total * 3000 / 10000;

        uint256 bal1Before = player1.balance;
        vm.prank(player1);
        game.claimPrize(gameId);
        assertEq(player1.balance - bal1Before, expected1);

        uint256 bal2Before = player2.balance;
        vm.prank(player2);
        game.claimPrize(gameId);
        assertEq(player2.balance - bal2Before, expected2);
    }

    // ============ Start Game After Deadline ============

    function test_startGame_afterRegistrationDeadline() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        // Warp past gameDate (which is after registration deadline)
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        vm.warp(config.gameDate);

        // Operator can still start game after deadline as long as minPlayers met
        vm.prank(operator);
        game.startGame(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.ACTIVE));
    }

    // ============ Reentrancy: withdrawCreatorFees ============

    function test_reentrancy_withdrawCreatorFees() public {
        ReentrancyAttacker attacker = new ReentrancyAttacker(game);
        address attackerAddr = address(attacker);
        vm.deal(attackerAddr, 10 ether);

        // Make attacker an operator so it can create games
        game.addOperator(attackerAddr);

        // Attacker creates a game (becomes creator)
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        vm.prank(attackerAddr);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // Attacker has creator fees — try reentrancy
        uint256 creatorFee = game.creatorFeesAccrued(attackerAddr);
        assertTrue(creatorFee > 0);

        uint256 balBefore = attackerAddr.balance;
        attacker.attackWithdrawCreatorFees(attackerAddr);

        // Should only receive creator fees once
        assertEq(attackerAddr.balance - balBefore, creatorFee);
        assertEq(game.creatorFeesAccrued(attackerAddr), 0);
    }

    // ============ Boundary Tests ============

    function test_createGame_titleExactMaxLength() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        bytes memory maxTitle = new bytes(256);
        for (uint256 i = 0; i < 256; i++) {
            maxTitle[i] = "A";
        }
        params.title = string(maxTitle);
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        assertEq(bytes(config.title).length, 256);
    }

    function test_createGame_maxPlayersExactLimit() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.maxPlayers = 9999; // exactly MAX_PLAYERS
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        assertEq(config.maxPlayers, 9999);
    }

    function test_startGame_atExactGameDate() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        // Warp to exactly gameDate (boundary — should succeed)
        vm.warp(config.gameDate);
        vm.prank(operator);
        game.startGame(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.ACTIVE));
    }

    // ============ Multi-Game Solvency ============

    function test_multiGameSolvency() public {
        // Run two games end-to-end, claim everything, verify zero balance.

        // Game 1: 4 players, full prizes
        uint256 gameId1 = _setupEndedGame();

        // Game 2: 3 players, different BPS
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 5000;
        params.bps2nd = 2000;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsCreator = 2000;
        params.minPlayers = 2;
        vm.prank(operator);
        uint256 gameId2 = game.createGame(params, _defaultShrinks());
        _registerPlayer(gameId2, player1);
        _registerPlayer(gameId2, player2);
        _registerPlayer(gameId2, player3);
        _startGame(gameId2);
        vm.prank(operator);
        game.endGame(gameId2, player1, player2, address(0), address(0));

        // Claim all game1 prizes
        vm.prank(player1); game.claimPrize(gameId1);
        vm.prank(player2); game.claimPrize(gameId1);
        vm.prank(player3); game.claimPrize(gameId1);
        vm.prank(player4); game.claimPrize(gameId1);

        // Claim all game2 prizes
        vm.prank(player1); game.claimPrize(gameId2);
        vm.prank(player2); game.claimPrize(gameId2);

        // Withdraw all creator fees (operator created both games)
        vm.prank(operator);
        game.withdrawCreatorFees(operator);

        // Withdraw platform fees
        game.withdrawPlatformFees(address(0xFEE));

        // Contract should have exactly zero balance
        assertEq(address(game).balance, 0);
    }
}
