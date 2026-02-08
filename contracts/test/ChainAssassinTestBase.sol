// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Test.sol";
import "../src/ChainAssassin.sol";

abstract contract ChainAssassinTestBase is Test {
    ChainAssassin public game;

    address public operator = address(0x1);
    address public player1 = address(0x10);
    address public player2 = address(0x20);
    address public player3 = address(0x30);
    address public player4 = address(0x40);
    address public player5 = address(0x50);

    uint128 constant ENTRY_FEE = 0.05 ether;
    uint16 constant MIN_PLAYERS = 3;
    uint16 constant MAX_PLAYERS = 100;

    function setUp() public virtual {
        game = new ChainAssassin();
        game.addOperator(operator);

        vm.deal(player1, 10 ether);
        vm.deal(player2, 10 ether);
        vm.deal(player3, 10 ether);
        vm.deal(player4, 10 ether);
        vm.deal(player5, 10 ether);
    }

    function _defaultParams() internal view returns (IChainAssassin.CreateGameParams memory) {
        return IChainAssassin.CreateGameParams({
            title: "Chain-Assassin CDMX #1",
            entryFee: ENTRY_FEE,
            minPlayers: MIN_PLAYERS,
            maxPlayers: MAX_PLAYERS,
            registrationDeadline: uint40(block.timestamp + 1 days),
            expiryDeadline: uint40(block.timestamp + 2 days),
            centerLat: 19435244,
            centerLng: -99128056,
            bps1st: 4000,
            bps2nd: 1500,
            bps3rd: 1000,
            bpsKills: 2500,
            bpsPlatform: 1000
        });
    }

    function _defaultShrinks() internal pure returns (IChainAssassin.ZoneShrink[] memory) {
        IChainAssassin.ZoneShrink[] memory shrinks = new IChainAssassin.ZoneShrink[](3);
        shrinks[0] = IChainAssassin.ZoneShrink(0, 2000);
        shrinks[1] = IChainAssassin.ZoneShrink(600, 1000);
        shrinks[2] = IChainAssassin.ZoneShrink(1200, 300);
        return shrinks;
    }

    function _createDefaultGame() internal returns (uint256) {
        vm.prank(operator);
        return game.createGame(_defaultParams(), _defaultShrinks());
    }

    function _registerPlayer(uint256 gameId, address player) internal {
        vm.prank(player);
        game.register{value: ENTRY_FEE}(gameId);
    }

    function _getPlayers() internal view returns (address[] memory) {
        address[] memory players = new address[](5);
        players[0] = player1;
        players[1] = player2;
        players[2] = player3;
        players[3] = player4;
        players[4] = player5;
        return players;
    }

    function _createAndRegisterPlayers(uint256 count) internal returns (uint256 gameId) {
        gameId = _createDefaultGame();
        address[] memory players = _getPlayers();
        for (uint256 i = 0; i < count && i < players.length; i++) {
            _registerPlayer(gameId, players[i]);
        }
    }

    /// @dev Creates a game, registers 4 players, starts, and ends with all winners set.
    /// player1=winner1, player2=winner2, player3=winner3, player4=topKiller
    function _setupEndedGame() internal returns (uint256 gameId) {
        gameId = _createAndRegisterPlayers(4);

        vm.prank(operator);
        game.startGame(gameId);

        vm.prank(operator);
        game.endGame(gameId, player1, player2, player3, player4);
    }
}
