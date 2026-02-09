// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./ChainAssassinTestBase.sol";

// Reentrancy attack contract
contract ReentrancyAttacker {
    ChainAssassin public target;
    uint256 public gameId;
    bool public attacking;

    constructor(ChainAssassin _target) {
        target = _target;
    }

    function setGameId(uint256 _gameId) external {
        gameId = _gameId;
    }

    function attackClaimPrize() external {
        attacking = true;
        target.claimPrize(gameId);
    }

    function attackClaimRefund() external {
        attacking = true;
        target.claimRefund(gameId);
    }

    receive() external payable {
        if (attacking) {
            attacking = false;
            // Try to re-enter
            try target.claimPrize(gameId) {} catch {}
            try target.claimRefund(gameId) {} catch {}
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

        vm.prank(operator);
        game.startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, attackerAddr, player1, player2, attackerAddr);

        attacker.setGameId(gameId);
        uint256 balBefore = attackerAddr.balance;

        attacker.attackClaimPrize();

        // Should only receive prize once (40% + 25% since winner1 + topKiller)
        uint256 totalPool = 0.15 ether; // 3 players
        uint256 expected = totalPool * (4000 + 2500) / 10000;
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
        vm.prank(operator);
        game.startGame(game1);
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

        vm.prank(operator);
        game.startGame(gameId);
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
        vm.prank(operator);
        game.startGame(gameId);
    }

    // ============ Expiry Tests ============

    function test_triggerExpiry_success() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        vm.prank(operator);
        game.startGame(gameId);

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

        vm.prank(operator);
        game.startGame(gameId);

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

        vm.prank(operator);
        game.startGame(gameId);

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

    function test_ownerCanActAsOperator() public {
        // Owner should be able to call operator functions directly
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        assertEq(gameId, 1);
    }

    // ============ 1-Player Game (Only 1st Place) ============

    function test_onePlayerGame_endToEnd() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 9000;
        params.bps2nd = 0;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsPlatform = 1000;
        params.minPlayers = 1;
        params.maxPlayers = 10;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);

        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, address(0), address(0), address(0));

        // player1 gets 90% of 0.05 ETH
        uint256 expected = 0.05 ether * 9000 / 10000;
        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimPrize(gameId);
        assertEq(player1.balance - balBefore, expected);
    }

    // ============ 2-Player Game (1st + 2nd Place) ============

    function test_twoPlayerGame_endToEnd() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 6000;
        params.bps2nd = 3000;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsPlatform = 1000;
        params.minPlayers = 2;
        params.maxPlayers = 10;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);

        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, address(0), address(0));

        uint256 total = 0.1 ether; // 2 * 0.05
        uint256 expected1 = total * 6000 / 10000;
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

        // Warp past registration deadline
        vm.warp(block.timestamp + 2 days);

        // Operator can still start game after deadline as long as minPlayers met
        vm.prank(operator);
        game.startGame(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.ACTIVE));
    }
}
