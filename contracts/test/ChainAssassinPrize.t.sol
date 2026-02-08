// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./ChainAssassinTestBase.sol";

contract ChainAssassinPrizeTest is ChainAssassinTestBase {
    // ============ Prize Claim Tests ============

    function test_claimPrize_winner1() public {
        uint256 gameId = _setupEndedGame();
        // 4 players * 0.05 ETH = 0.2 ETH total
        // 1st = 40% of 0.2 = 0.08 ETH
        uint256 expected = 0.2 ether * 4000 / 10000;

        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimPrize(gameId);
        assertEq(player1.balance - balBefore, expected);
    }

    function test_claimPrize_winner2() public {
        uint256 gameId = _setupEndedGame();
        uint256 expected = 0.2 ether * 1500 / 10000;

        uint256 balBefore = player2.balance;
        vm.prank(player2);
        game.claimPrize(gameId);
        assertEq(player2.balance - balBefore, expected);
    }

    function test_claimPrize_winner3() public {
        uint256 gameId = _setupEndedGame();
        uint256 expected = 0.2 ether * 1000 / 10000;

        uint256 balBefore = player3.balance;
        vm.prank(player3);
        game.claimPrize(gameId);
        assertEq(player3.balance - balBefore, expected);
    }

    function test_claimPrize_topKiller() public {
        uint256 gameId = _setupEndedGame();
        uint256 expected = 0.2 ether * 2500 / 10000;

        uint256 balBefore = player4.balance;
        vm.prank(player4);
        game.claimPrize(gameId);
        assertEq(player4.balance - balBefore, expected);
    }

    function test_claimPrize_combinedWinner1AndTopKiller() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        vm.prank(operator);
        game.startGame(gameId);

        // player1 is both winner1 AND topKiller
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // 3 players * 0.05 = 0.15 ETH total
        // player1 gets 40% + 25% = 65%
        uint256 expected = 0.15 ether * (4000 + 2500) / 10000;

        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimPrize(gameId);
        assertEq(player1.balance - balBefore, expected);
    }

    function test_claimPrize_revertsDoubleClaim() public {
        uint256 gameId = _setupEndedGame();

        vm.prank(player1);
        game.claimPrize(gameId);

        vm.prank(player1);
        vm.expectRevert("Already claimed");
        game.claimPrize(gameId);
    }

    function test_claimPrize_revertsNonWinner() public {
        uint256 gameId = _setupEndedGame();

        vm.prank(player5);
        vm.expectRevert("No prize");
        game.claimPrize(gameId);
    }

    function test_allPrizesPlusPlatformSumCorrectly() public {
        uint256 gameId = _setupEndedGame();
        uint256 total = 0.2 ether; // 4 * 0.05

        uint256 p1 = game.getClaimableAmount(gameId, player1);
        uint256 p2 = game.getClaimableAmount(gameId, player2);
        uint256 p3 = game.getClaimableAmount(gameId, player3);
        uint256 p4 = game.getClaimableAmount(gameId, player4);
        uint256 platform = total * 1000 / 10000;

        // Sum should equal total (with possible dust of a few wei)
        uint256 sum = p1 + p2 + p3 + p4 + platform;
        assertApproxEqAbs(sum, total, 4); // at most 4 wei dust from rounding
    }

    function test_endGame_revertsIfWinner2SameAsWinner1() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert("Winner2 same as winner1");
        game.endGame(gameId, player1, player1, player3, player1);
    }

    function test_endGame_revertsIfWinner3SameAsWinner1() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert("Winner3 not unique");
        game.endGame(gameId, player1, player2, player1, player1);
    }

    function test_endGame_revertsIfWinner3SameAsWinner2() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert("Winner3 not unique");
        game.endGame(gameId, player1, player2, player2, player1);
    }

    // ============ Platform Fee Tests ============

    function test_platformFeeWithdraw() public {
        _setupEndedGame();
        uint256 expectedFee = 0.2 ether * 1000 / 10000; // 10% of 0.2 ETH

        assertEq(game.platformFeesAccrued(), expectedFee);

        address feeRecipient = address(0xFEE);
        uint256 balBefore = feeRecipient.balance;
        game.withdrawPlatformFees(feeRecipient);
        assertEq(feeRecipient.balance - balBefore, expectedFee);
        assertEq(game.platformFeesAccrued(), 0);
    }

    function test_platformFeeWithdraw_revertsNonOwner() public {
        _setupEndedGame();
        vm.prank(player1);
        vm.expectRevert();
        game.withdrawPlatformFees(player1);
    }

    function test_zeroPlatformFeeGame() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bpsPlatform = 0;
        params.bps1st = 5000; // adjust to sum 10000
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        vm.prank(operator);
        game.startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        assertEq(game.platformFeesAccrued(), 0);
    }

    // ============ Refund Tests ============

    function test_triggerCancellation_success() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2); // 2 players, min is 3

        // Warp past deadline
        vm.warp(block.timestamp + 2 days);

        vm.expectEmit(true, false, false, true);
        emit IChainAssassin.GameCancelled(gameId);
        game.triggerCancellation(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.CANCELLED));
    }

    function test_triggerCancellation_revertsBeforeDeadline() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);

        vm.expectRevert("Deadline not passed");
        game.triggerCancellation(gameId);
    }

    function test_triggerCancellation_revertsIfEnoughPlayers() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3); // 3 players = minPlayers

        vm.warp(block.timestamp + 2 days);

        vm.expectRevert("Enough players");
        game.triggerCancellation(gameId);
    }

    function test_triggerCancellation_atExactMinPlayers() public {
        // Exactly at minPlayers should revert (we have enough)
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.minPlayers = 3;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        vm.warp(block.timestamp + 2 days);

        vm.expectRevert("Enough players");
        game.triggerCancellation(gameId);
    }

    function test_claimRefund_success() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);

        vm.warp(block.timestamp + 2 days);
        game.triggerCancellation(gameId);

        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimRefund(gameId);
        assertEq(player1.balance - balBefore, ENTRY_FEE);
    }

    function test_claimRefund_revertsDoubleClaim() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);

        vm.warp(block.timestamp + 2 days);
        game.triggerCancellation(gameId);

        vm.prank(player1);
        game.claimRefund(gameId);

        vm.prank(player1);
        vm.expectRevert("Already claimed");
        game.claimRefund(gameId);
    }

    function test_claimRefund_revertsNotRegistered() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);

        vm.warp(block.timestamp + 2 days);
        game.triggerCancellation(gameId);

        vm.prank(player2); // not registered
        vm.expectRevert("Not registered");
        game.claimRefund(gameId);
    }
}
