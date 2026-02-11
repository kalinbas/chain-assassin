// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./ChainAssassinTestBase.sol";

contract ChainAssassinPrizeTest is ChainAssassinTestBase {
    // ============ Prize Claim Tests ============

    function test_claimPrize_winner1() public {
        uint256 gameId = _setupEndedGame();
        // 4 players * 0.05 ETH = 0.2 ETH total
        // 1st = 35% of 0.2 = 0.07 ETH
        uint256 expected = 0.2 ether * 3500 / 10000;

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
        uint256 expected = 0.2 ether * 2000 / 10000;

        uint256 balBefore = player4.balance;
        vm.prank(player4);
        game.claimPrize(gameId);
        assertEq(player4.balance - balBefore, expected);
    }

    function test_claimPrize_combinedWinner1AndTopKiller() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        _startGame(gameId);

        // player1 is both winner1 AND topKiller
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // 3 players * 0.05 = 0.15 ETH total
        // player1 gets 35% + 20% = 55%
        uint256 expected = 0.15 ether * (3500 + 2000) / 10000;

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
        vm.expectRevert(IChainAssassin.AlreadyClaimed.selector);
        game.claimPrize(gameId);
    }

    function test_claimPrize_revertsNonWinner() public {
        uint256 gameId = _setupEndedGame();

        vm.prank(player5);
        vm.expectRevert(IChainAssassin.NoPrize.selector);
        game.claimPrize(gameId);
    }

    function test_allPrizesPlusFeesSumCorrectly() public {
        uint256 gameId = _setupEndedGame();
        uint256 total = 0.2 ether; // 4 * 0.05

        uint256 p1 = game.getClaimableAmount(gameId, player1);
        uint256 p2 = game.getClaimableAmount(gameId, player2);
        uint256 p3 = game.getClaimableAmount(gameId, player3);
        uint256 p4 = game.getClaimableAmount(gameId, player4);
        uint256 platform = game.platformFeesAccrued();
        uint256 creator = game.creatorFeesAccrued(operator);

        // Platform fee absorbs rounding dust, so sum must be exactly total
        uint256 sum = p1 + p2 + p3 + p4 + platform + creator;
        assertEq(sum, total);
    }

    function test_endGame_revertsIfWinner2SameAsWinner1() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        _startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnersNotUnique.selector);
        game.endGame(gameId, player1, player1, player3, player1);
    }

    function test_endGame_revertsIfWinner3SameAsWinner1() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        _startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnersNotUnique.selector);
        game.endGame(gameId, player1, player2, player1, player1);
    }

    function test_endGame_revertsIfWinner3SameAsWinner2() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        _startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnersNotUnique.selector);
        game.endGame(gameId, player1, player2, player2, player1);
    }

    // ============ Platform Fee Tests ============

    function test_platformFeeWithdraw() public {
        _setupEndedGame();
        // Platform fee is the remainder: total - prizes - creatorFee (absorbs rounding dust)
        uint256 total = 0.2 ether; // 4 players * 0.05 ETH
        uint256 prizes = total * 3500 / 10000  // bps1st
                       + total * 1500 / 10000  // bps2nd
                       + total * 1000 / 10000  // bps3rd
                       + total * 2000 / 10000; // bpsKills
        uint256 creatorFee = total * 1000 / 10000; // bpsCreator
        uint256 expectedFee = total - prizes - creatorFee;

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

    function test_zeroCreatorFeeGame() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bpsCreator = 0;
        params.bps1st = 4500; // adjust to sum 9000 (+ 1000 platform = 10000)
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // Creator fees should be 0 (operator is the game creator)
        assertEq(game.creatorFeesAccrued(operator), 0);
        // Platform fees should still be accrued
        assertTrue(game.platformFeesAccrued() > 0);
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

        vm.expectRevert(IChainAssassin.DeadlineNotPassed.selector);
        game.triggerCancellation(gameId);
    }

    function test_triggerCancellation_revertsIfEnoughPlayers() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3); // 3 players = minPlayers

        vm.warp(block.timestamp + 2 days);

        vm.expectRevert(IChainAssassin.EnoughPlayers.selector);
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

        vm.expectRevert(IChainAssassin.EnoughPlayers.selector);
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
        vm.expectRevert(IChainAssassin.AlreadyClaimed.selector);
        game.claimRefund(gameId);
    }

    function test_claimRefund_revertsNotRegistered() public {
        vm.prank(operator);
        uint256 gameId = game.createGame(_defaultParams(), _defaultShrinks());
        _registerPlayer(gameId, player1);

        vm.warp(block.timestamp + 2 days);
        game.triggerCancellation(gameId);

        vm.prank(player2); // not registered
        vm.expectRevert(IChainAssassin.PlayerNotRegistered.selector);
        game.claimRefund(gameId);
    }

    // ============ Creator Fee Tests ============

    function test_creatorFeeAccrued() public {
        _setupEndedGame();
        // operator created the game; bpsCreator = 1000, total = 0.2 ETH
        uint256 expectedCreatorFee = 0.2 ether * 1000 / 10000;
        assertEq(game.creatorFeesAccrued(operator), expectedCreatorFee);
    }

    function test_creatorFeeWithdraw() public {
        _setupEndedGame();
        uint256 expectedCreatorFee = 0.2 ether * 1000 / 10000;

        address feeRecipient = address(0xCCC);
        uint256 balBefore = feeRecipient.balance;

        vm.prank(operator);
        game.withdrawCreatorFees(feeRecipient);

        assertEq(feeRecipient.balance - balBefore, expectedCreatorFee);
        assertEq(game.creatorFeesAccrued(operator), 0);
    }

    function test_creatorFeeWithdraw_revertsZeroAddress() public {
        _setupEndedGame();
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.ZeroAddress.selector);
        game.withdrawCreatorFees(address(0));
    }

    function test_creatorFeeWithdraw_revertsWhenNoFees() public {
        vm.prank(player1);
        vm.expectRevert(IChainAssassin.NoFees.selector);
        game.withdrawCreatorFees(player1);
    }

    function test_creatorFeeWithdraw_multipleGames() public {
        // Create and end two games, both by same operator
        _setupEndedGame();                // game 1: 4 players × 0.05 = 0.2 ETH
        uint256 gameId2 = _createAndRegisterPlayers(3);
        _startGame(gameId2);
        vm.prank(operator);
        game.endGame(gameId2, player1, player2, player3, player1);

        // game 2: 3 players × 0.05 = 0.15 ETH
        uint256 expectedFee = (0.2 ether * 1000 / 10000) + (0.15 ether * 1000 / 10000);
        assertEq(game.creatorFeesAccrued(operator), expectedFee);
    }

    // ============ Platform Fee BPS Management Tests ============

    function test_setPlatformFeeBps() public {
        assertEq(game.platformFeeBps(), PLATFORM_FEE_BPS);

        game.setPlatformFeeBps(500);
        assertEq(game.platformFeeBps(), 500);
    }

    function test_setPlatformFeeBps_revertsNonOwner() public {
        vm.prank(player1);
        vm.expectRevert();
        game.setPlatformFeeBps(500);
    }

    function test_setPlatformFeeBps_revertsTooHigh() public {
        vm.expectRevert(IChainAssassin.PlatformFeeTooHigh.selector);
        game.setPlatformFeeBps(5001);
    }

    function test_setPlatformFeeBps_affectsNewGames() public {
        // Change platform fee to 500
        game.setPlatformFeeBps(500);

        // Old default params sum to 9000 game BPS, but now platform is 500
        // So we need game BPS = 9500
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 4000; // increase to compensate for lower platform
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // Platform fee is the remainder (absorbs dust)
        uint256 total = 0.15 ether; // 3 players * 0.05 ETH
        // Game BPS: 4000 + 1500 + 1000 + 2000 + 1000 = 9500 → platform = 500
        uint256 prizes = total * 4000 / 10000
                       + total * 1500 / 10000
                       + total * 1000 / 10000
                       + total * 2000 / 10000;
        uint256 creatorFee = total * 1000 / 10000;
        uint256 expectedPlatformFee = total - prizes - creatorFee;
        assertEq(game.platformFeesAccrued(), expectedPlatformFee);
    }

    function test_platformFeeLockedAtCreationTime() public {
        // Create a game while platformFeeBps = 1000 (default).
        // Game BPS: 3500+1500+1000+2000+1000 = 9000, implicit platform = 1000.
        uint256 gameId = _createDefaultGame();

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        _startGame(gameId);

        // Owner changes platform fee to 500 AFTER the game was created.
        game.setPlatformFeeBps(500);

        // End the game — platform fee should still be 1000 (locked at creation).
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // Verify: platform fee is derived from stored game BPS, not current global rate.
        uint256 total = 0.15 ether; // 3 players * 0.05 ETH
        uint256 prizes = total * 3500 / 10000
                       + total * 1500 / 10000
                       + total * 1000 / 10000
                       + total * 2000 / 10000;
        uint256 creatorFee = total * 1000 / 10000;
        uint256 expectedPlatformFee = total - prizes - creatorFee;
        assertEq(game.platformFeesAccrued(), expectedPlatformFee);

        // Also verify the fee corresponds to ~10% (1000 BPS), not ~5% (500 BPS).
        uint256 approx10pct = total * 1000 / 10000;
        assertApproxEqAbs(game.platformFeesAccrued(), approx10pct, 5);
    }

    function test_noDustLockedInContract() public {
        // End a game, claim all prizes and withdraw all fees, verify zero balance.
        uint256 gameId = _setupEndedGame();

        // Claim all prizes
        vm.prank(player1); game.claimPrize(gameId); // winner1
        vm.prank(player2); game.claimPrize(gameId); // winner2
        vm.prank(player3); game.claimPrize(gameId); // winner3
        vm.prank(player4); game.claimPrize(gameId); // topKiller

        // Withdraw creator fees
        vm.prank(operator);
        game.withdrawCreatorFees(operator);

        // Withdraw platform fees
        game.withdrawPlatformFees(address(0xFEE));

        // Contract should have zero balance — no dust locked
        assertEq(address(game).balance, 0);
    }

    // ============ Base Reward Tests ============

    function test_baseReward_addedToTotalCollected() public {
        uint256 gameId = _createGameWithBaseReward(BASE_REWARD);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.totalCollected, BASE_REWARD);

        // Register 3 players
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        state = game.getGameState(gameId);
        assertEq(state.totalCollected, BASE_REWARD + 3 * ENTRY_FEE);
    }

    function test_baseReward_storedInConfig() public {
        uint256 gameId = _createGameWithBaseReward(BASE_REWARD);

        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        assertEq(config.baseReward, BASE_REWARD);
    }

    function test_baseReward_prizeDistribution() public {
        uint256 gameId = _createGameWithBaseReward(BASE_REWARD);

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // total = 0.5 + 3 * 0.05 = 0.65 ETH
        uint256 total = BASE_REWARD + 3 * ENTRY_FEE;
        uint256 expected1st = total * 3500 / 10000;

        assertEq(game.getClaimableAmount(gameId, player1), expected1st + total * 2000 / 10000); // winner1 + topKiller
        assertEq(game.getClaimableAmount(gameId, player2), total * 1500 / 10000);
        assertEq(game.getClaimableAmount(gameId, player3), total * 1000 / 10000);
    }

    function test_baseReward_noDustWithBaseReward() public {
        uint256 gameId = _createGameWithBaseReward(BASE_REWARD);

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);
        _registerPlayer(gameId, player4);

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player4);

        // Claim all prizes
        vm.prank(player1); game.claimPrize(gameId);
        vm.prank(player2); game.claimPrize(gameId);
        vm.prank(player3); game.claimPrize(gameId);
        vm.prank(player4); game.claimPrize(gameId);

        // Withdraw creator fees
        vm.prank(operator);
        game.withdrawCreatorFees(operator);

        // Withdraw platform fees
        game.withdrawPlatformFees(address(0xFEE));

        // Contract should have zero balance
        assertEq(address(game).balance, 0);
    }

    function test_baseReward_cancellationRefund() public {
        uint256 gameId = _createGameWithBaseReward(BASE_REWARD);
        _registerPlayer(gameId, player1); // 1 player, min is 3

        // Warp past deadline
        vm.warp(block.timestamp + 2 days);
        game.triggerCancellation(gameId);

        // Creator should have base reward accrued
        assertEq(game.creatorFeesAccrued(operator), BASE_REWARD);

        // Player claims entry fee refund
        uint256 balBefore = player1.balance;
        vm.prank(player1);
        game.claimRefund(gameId);
        assertEq(player1.balance - balBefore, ENTRY_FEE);

        // Creator withdraws base reward
        uint256 opBalBefore = operator.balance;
        vm.prank(operator);
        game.withdrawCreatorFees(operator);
        assertEq(operator.balance - opBalBefore, BASE_REWARD);

        // Contract should have zero balance
        assertEq(address(game).balance, 0);
    }

    function test_baseReward_expiryRefund() public {
        uint256 gameId = _createGameWithBaseReward(BASE_REWARD);
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        // Start the game
        _startGame(gameId);

        // Warp past expiry (gameDate + maxDuration)
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        vm.warp(uint256(config.gameDate) + config.maxDuration + 1);

        game.triggerExpiry(gameId);

        // Creator should have base reward accrued
        assertEq(game.creatorFeesAccrued(operator), BASE_REWARD);

        // All players claim refunds
        vm.prank(player1); game.claimRefund(gameId);
        vm.prank(player2); game.claimRefund(gameId);
        vm.prank(player3); game.claimRefund(gameId);

        // Creator withdraws
        vm.prank(operator);
        game.withdrawCreatorFees(operator);

        assertEq(address(game).balance, 0);
    }

    function test_baseReward_zeroBaseReward() public {
        // No base reward — backward compatible
        uint256 gameId = _createDefaultGame();

        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        assertEq(config.baseReward, 0);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.totalCollected, 0);
    }

    function test_baseReward_freeGameWithBaseReward() public {
        // entryFee = 0, base reward = 1 ETH
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.entryFee = 0;

        vm.prank(operator);
        uint256 gameId = game.createGame{value: 1 ether}(params, _defaultShrinks());

        // Register players (free)
        vm.prank(player1); game.register(gameId);
        vm.prank(player2); game.register(gameId);
        vm.prank(player3); game.register(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.totalCollected, 1 ether); // only base reward

        _startGame(gameId);
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        // 1st place gets 35% of 1 ETH = 0.35 ETH, plus topKiller 20% = 0.55 ETH
        uint256 expected = 1 ether * (3500 + 2000) / 10000;
        assertEq(game.getClaimableAmount(gameId, player1), expected);

        // Claim all + withdraw all → zero balance
        vm.prank(player1); game.claimPrize(gameId);
        vm.prank(player2); game.claimPrize(gameId);
        vm.prank(player3); game.claimPrize(gameId);
        vm.prank(operator); game.withdrawCreatorFees(operator);
        game.withdrawPlatformFees(address(0xFEE));

        assertEq(address(game).balance, 0);
    }
}
