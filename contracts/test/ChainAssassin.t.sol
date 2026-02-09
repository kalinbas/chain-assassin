// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./ChainAssassinTestBase.sol";

contract ChainAssassinTest is ChainAssassinTestBase {
    // ============ Create Game Tests ============

    function test_createGame_emitsEvent() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        IChainAssassin.ZoneShrink[] memory shrinks = _defaultShrinks();

        vm.prank(operator);
        vm.expectEmit(true, false, false, true);
        emit IChainAssassin.GameCreated(1, params.title, params.entryFee, params.minPlayers, params.maxPlayers, params.centerLat, params.centerLng);
        game.createGame(params, shrinks);
    }

    function test_createGame_storesConfig() public {
        uint256 gameId = _createDefaultGame();
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);

        assertEq(config.entryFee, ENTRY_FEE);
        assertEq(config.minPlayers, MIN_PLAYERS);
        assertEq(config.maxPlayers, MAX_PLAYERS);
        assertEq(config.bps1st, 4000);
        assertEq(config.bpsPlatform, 1000);
        assertEq(config.centerLat, 19435244);
    }

    function test_createGame_storesZoneShrinks() public {
        uint256 gameId = _createDefaultGame();
        IChainAssassin.ZoneShrink[] memory shrinks = game.getZoneShrinks(gameId);

        assertEq(shrinks.length, 3);
        assertEq(shrinks[0].radiusMeters, 2000);
        assertEq(shrinks[1].atSecond, 600);
        assertEq(shrinks[2].radiusMeters, 300);
    }

    function test_createGame_revertsIfNotOperator() public {
        vm.prank(player1);
        vm.expectRevert(IChainAssassin.NotOperator.selector);
        game.createGame(_defaultParams(), _defaultShrinks());
    }

    function test_createGame_revertsIfBpsMismatch() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 5000; // sum = 11000
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.BpsSumNot10000.selector);
        game.createGame(params, _defaultShrinks());
    }

    function test_createGame_revertsIfNoShrinks() public {
        IChainAssassin.ZoneShrink[] memory empty = new IChainAssassin.ZoneShrink[](0);
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.NoShrinkSchedule.selector);
        game.createGame(_defaultParams(), empty);
    }

    function test_createGame_revertsIfBps1stZero() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 0;
        params.bps2nd = 0;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsPlatform = 10000;
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.NeedFirstPrize.selector);
        game.createGame(params, _defaultShrinks());
    }

    function test_createGame_revertsIfBps3rdWithoutBps2nd() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 7000;
        params.bps2nd = 0;
        params.bps3rd = 1000;
        params.bpsKills = 1000;
        params.bpsPlatform = 1000;
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.Need2ndIf3rdSet.selector);
        game.createGame(params, _defaultShrinks());
    }

    function test_createGame_revertsIfMinPlayersLessThanPrizeSlots() public {
        // bps1st + bps2nd + bps3rd all > 0, so need minPlayers >= 3
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.minPlayers = 2; // too low for 3-tier prizes
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.MinPlayersLessThanPrizeSlots.selector);
        game.createGame(params, _defaultShrinks());
    }

    function test_createGame_onlyBps1st_minPlayers1() public {
        // Only 1st place prize: minPlayers can be 1
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
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        assertEq(config.minPlayers, 1);
    }

    function test_createGame_bps1stAnd2nd_minPlayers2() public {
        // 1st + 2nd place: minPlayers can be 2
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
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);
        assertEq(config.minPlayers, 2);
    }

    function test_createGame_bps2ndOnly_revertsMinPlayers1() public {
        // 1st + 2nd place: minPlayers 1 is too low
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 6000;
        params.bps2nd = 3000;
        params.bps3rd = 0;
        params.bpsKills = 0;
        params.bpsPlatform = 1000;
        params.minPlayers = 1;
        params.maxPlayers = 10;
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.MinPlayersLessThanPrizeSlots.selector);
        game.createGame(params, _defaultShrinks());
    }

    // ============ Registration Tests ============

    function test_register_success() public {
        uint256 gameId = _createDefaultGame();

        vm.prank(player1);
        vm.expectEmit(true, true, false, true);
        emit IChainAssassin.PlayerRegistered(gameId, player1, 1);
        game.register{value: ENTRY_FEE}(gameId);

        (bool registered, bool alive,,) = game.getPlayerInfo(gameId, player1);
        assertTrue(registered);
        assertTrue(alive);
    }

    function test_register_revertsWrongFee() public {
        uint256 gameId = _createDefaultGame();
        vm.prank(player1);
        vm.expectRevert(IChainAssassin.WrongEntryFee.selector);
        game.register{value: 0.01 ether}(gameId);
    }

    function test_register_revertsIfFull() public {
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.maxPlayers = 3;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        vm.prank(player4);
        vm.expectRevert(IChainAssassin.GameFull.selector);
        game.register{value: ENTRY_FEE}(gameId);
    }

    function test_register_revertsIfAlreadyRegistered() public {
        uint256 gameId = _createDefaultGame();
        _registerPlayer(gameId, player1);

        vm.prank(player1);
        vm.expectRevert(IChainAssassin.AlreadyRegistered.selector);
        game.register{value: ENTRY_FEE}(gameId);
    }

    function test_register_revertsIfNotRegistrationPhase() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(player4);
        vm.expectRevert(IChainAssassin.WrongPhase.selector);
        game.register{value: ENTRY_FEE}(gameId);
    }

    function test_register_revertsAfterDeadline() public {
        uint256 gameId = _createDefaultGame();

        vm.warp(block.timestamp + 2 days);

        vm.prank(player1);
        vm.expectRevert(IChainAssassin.RegistrationClosed.selector);
        game.register{value: ENTRY_FEE}(gameId);
    }

    function test_register_allowedAtExactDeadline() public {
        uint256 gameId = _createDefaultGame();
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);

        vm.warp(config.registrationDeadline);

        vm.prank(player1);
        game.register{value: ENTRY_FEE}(gameId);

        (bool registered,,,) = game.getPlayerInfo(gameId, player1);
        assertTrue(registered);
    }

    // ============ Start Game Tests ============

    function test_startGame_success() public {
        uint256 gameId = _createAndRegisterPlayers(3);

        vm.prank(operator);
        vm.expectEmit(true, false, false, true);
        emit IChainAssassin.GameStarted(gameId, 3);
        game.startGame(gameId);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.ACTIVE));
    }

    function test_startGame_revertsIfNotEnoughPlayers() public {
        uint256 gameId = _createDefaultGame();
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2); // only 2, need 3

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.NotEnoughPlayers.selector);
        game.startGame(gameId);
    }

    function test_startGame_revertsIfNotOperator() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(player1);
        vm.expectRevert(IChainAssassin.NotOperator.selector);
        game.startGame(gameId);
    }

    // ============ Kill Tests ============

    function test_recordKill_success() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.recordKill(gameId, player1, player2);

        (, bool alive, uint16 kills,) = game.getPlayerInfo(gameId, player1);
        assertTrue(alive);
        assertEq(kills, 1);

        (, bool targetAlive,,) = game.getPlayerInfo(gameId, player2);
        assertFalse(targetAlive);
    }

    function test_recordKill_revertsIfTargetDead() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.recordKill(gameId, player1, player2);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.TargetNotAlive.selector);
        game.recordKill(gameId, player3, player2);
    }

    function test_recordKill_revertsIfSelfKill() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.CannotSelfKill.selector);
        game.recordKill(gameId, player1, player1);
    }

    function test_recordKill_revertsIfHunterDead() public {
        uint256 gameId = _createAndRegisterPlayers(4);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.recordKill(gameId, player2, player1);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.HunterNotAlive.selector);
        game.recordKill(gameId, player1, player3);
    }

    // ============ Eliminate Tests ============

    function test_eliminatePlayer_success() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectEmit(true, true, true, true);
        emit IChainAssassin.PlayerEliminated(gameId, player1, address(0));
        game.eliminatePlayer(gameId, player1);

        (, bool alive,,) = game.getPlayerInfo(gameId, player1);
        assertFalse(alive);
    }

    function test_eliminatePlayer_revertsIfAlreadyDead() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.eliminatePlayer(gameId, player1);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.PlayerNotAlive.selector);
        game.eliminatePlayer(gameId, player1);
    }

    // ============ End Game Tests ============

    function test_endGame_success() public {
        uint256 gameId = _createAndRegisterPlayers(4);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player4);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(uint8(state.phase), uint8(IChainAssassin.GamePhase.ENDED));
        assertEq(state.winner1, player1);
        assertEq(state.winner2, player2);
        assertEq(state.winner3, player3);
        assertEq(state.topKiller, player4);
    }

    function test_endGame_revertsIfWinnerNotRegistered() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        address unregistered = address(0x99);
        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnerNotRegistered.selector);
        game.endGame(gameId, unregistered, player2, player3, player1);
    }

    function test_endGame_revertsIfWinner1Zero() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnerZeroAddress.selector);
        game.endGame(gameId, address(0), player2, player3, player1);
    }

    function test_endGame_revertsIfWinner2ZeroWhenBps2ndSet() public {
        // Default config has bps2nd > 0, so winner2 must be non-zero
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnerZeroAddress.selector);
        game.endGame(gameId, player1, address(0), player3, player1);
    }

    function test_endGame_revertsIfWinner3ZeroWhenBps3rdSet() public {
        // Default config has bps3rd > 0, so winner3 must be non-zero
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.WinnerZeroAddress.selector);
        game.endGame(gameId, player1, player2, address(0), player1);
    }

    function test_endGame_revertsIfTopKillerZeroWhenBpsKillsSet() public {
        // Default config has bpsKills > 0, so topKiller must be non-zero
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        vm.expectRevert(IChainAssassin.TopKillerZeroAddress.selector);
        game.endGame(gameId, player1, player2, player3, address(0));
    }

    function test_endGame_allowsZeroWinner2WhenBps2ndZero() public {
        // Only 1st place prize — winner2 can be address(0)
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

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.winner1, player1);
        assertEq(state.winner2, address(0));
        assertEq(state.winner3, address(0));
        assertEq(state.topKiller, address(0));
    }

    function test_endGame_allowsZeroWinner3WhenBps3rdZero() public {
        // 1st + 2nd only — winner3 can be address(0)
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

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.winner1, player1);
        assertEq(state.winner2, player2);
        assertEq(state.winner3, address(0));
    }

    function test_endGame_allowsZeroTopKillerWhenBpsKillsZero() public {
        // All 3 tiers but no kills bonus
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 5000;
        params.bps2nd = 2000;
        params.bps3rd = 2000;
        params.bpsKills = 0;
        params.bpsPlatform = 1000;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        _registerPlayer(gameId, player3);

        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, address(0));

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.topKiller, address(0));
    }

    function test_endGame_topKillerCanOverlapWithWinner() public {
        uint256 gameId = _createAndRegisterPlayers(3);
        vm.prank(operator);
        game.startGame(gameId);

        // player1 is both winner1 and topKiller
        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        IChainAssassin.GameState memory state = game.getGameState(gameId);
        assertEq(state.winner1, player1);
        assertEq(state.topKiller, player1);
    }
}
