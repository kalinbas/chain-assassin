// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./ChainAssassinTestBase.sol";

contract ChainAssassinFuzzTest is ChainAssassinTestBase {
    /// @dev Fuzz: any valid BPS split should produce prizes + fees == totalCollected.
    ///      Generates random BPS values that sum to 9000 (leaving 1000 for platform).
    function testFuzz_prizesSumToTotal(
        uint16 bps1st,
        uint16 bps2nd,
        uint16 bps3rd,
        uint16 bpsKills,
        uint16 bpsCreator
    ) public {
        // --- Constrain BPS so they form a valid config ---
        // bps1st must be > 0
        bps1st = uint16(bound(bps1st, 1, 9000));

        // Remaining budget after 1st
        uint16 remaining = 9000 - bps1st;

        bps2nd = uint16(bound(bps2nd, 0, remaining));
        remaining -= bps2nd;

        bps3rd = uint16(bound(bps3rd, 0, remaining));
        remaining -= bps3rd;

        bpsKills = uint16(bound(bpsKills, 0, remaining));
        remaining -= bpsKills;

        bpsCreator = remaining; // absorb the rest

        // Skip invalid prize configs
        if (bps3rd > 0 && bps2nd == 0) return;

        // Determine minimum players needed
        uint16 minP = 2;
        if (bps2nd > 0) minP = 2;
        if (bps3rd > 0) minP = 3;

        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = bps1st;
        params.bps2nd = bps2nd;
        params.bps3rd = bps3rd;
        params.bpsKills = bpsKills;
        params.bpsCreator = bpsCreator;
        params.minPlayers = minP;
        params.maxPlayers = 100;

        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        // Register exactly minP players (up to 3)
        _registerPlayer(gameId, player1);
        _registerPlayer(gameId, player2);
        if (minP >= 3) _registerPlayer(gameId, player3);

        _startGame(gameId);

        // End game: pick winners from registered players
        address w2 = bps2nd > 0 ? player2 : address(0);
        address w3 = bps3rd > 0 ? player3 : address(0);
        address tk = bpsKills > 0 ? player1 : address(0);

        vm.prank(operator);
        game.endGame(gameId, player1, w2, w3, tk);

        // --- Verify solvency ---
        uint256 total = uint256(ENTRY_FEE) * minP;

        // Sum claimable amounts for all registered players
        uint256 claimable = game.getClaimableAmount(gameId, player1)
                          + game.getClaimableAmount(gameId, player2);
        if (minP >= 3) claimable += game.getClaimableAmount(gameId, player3);

        uint256 platformFee = game.platformFeesAccrued();
        uint256 creatorFee = game.creatorFeesAccrued(operator);

        // Total distributed must exactly equal total collected
        assertEq(claimable + platformFee + creatorFee, total, "Solvency: sum != total");
    }

    /// @dev Fuzz: random entry fees should not break prize math.
    function testFuzz_entryFeeDoesNotBreakMath(uint128 entryFee) public {
        // Bound to reasonable range (0 to 10 ETH)
        entryFee = uint128(bound(entryFee, 0, 10 ether));

        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.entryFee = entryFee;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        // Deal enough to all players
        vm.deal(player1, 100 ether);
        vm.deal(player2, 100 ether);
        vm.deal(player3, 100 ether);

        vm.prank(player1); game.register{value: entryFee}(gameId);
        vm.prank(player2); game.register{value: entryFee}(gameId);
        vm.prank(player3); game.register{value: entryFee}(gameId);

        _startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player1);

        uint256 total = uint256(entryFee) * 3;

        uint256 claimable = game.getClaimableAmount(gameId, player1)
                          + game.getClaimableAmount(gameId, player2)
                          + game.getClaimableAmount(gameId, player3);

        uint256 platformFee = game.platformFeesAccrued();
        uint256 creatorFee = game.creatorFeesAccrued(operator);

        assertEq(claimable + platformFee + creatorFee, total, "Solvency with random fee");
    }

    /// @dev Fuzz: random player counts (2-5) should not break anything.
    function testFuzz_playerCount(uint8 rawCount) public {
        // 2 to 5 players
        uint256 count = bound(rawCount, 2, 5);

        // Use a 2-player-compatible config (no 3rd place prize)
        IChainAssassin.CreateGameParams memory params = _defaultParams();
        params.bps1st = 5000;
        params.bps2nd = 2000;
        params.bps3rd = 0;
        params.bpsKills = 1000;
        params.bpsCreator = 1000;
        params.minPlayers = 2;
        vm.prank(operator);
        uint256 gameId = game.createGame(params, _defaultShrinks());

        address[] memory players = _getPlayers();
        for (uint256 i = 0; i < count; i++) {
            _registerPlayer(gameId, players[i]);
        }

        _startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, address(0), player1);

        uint256 total = uint256(ENTRY_FEE) * count;

        uint256 claimable;
        for (uint256 i = 0; i < count; i++) {
            claimable += game.getClaimableAmount(gameId, players[i]);
        }

        uint256 platformFee = game.platformFeesAccrued();
        uint256 creatorFee = game.creatorFeesAccrued(operator);

        assertEq(claimable + platformFee + creatorFee, total, "Solvency with random players");
    }
}
