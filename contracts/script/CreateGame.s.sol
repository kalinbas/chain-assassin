// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console} from "forge-std/Script.sol";
import {ChainAssassin} from "../src/ChainAssassin.sol";
import {IChainAssassin} from "../src/IChainAssassin.sol";

/// @title CreateGame — Creates a test game on the deployed contract
contract CreateGame is Script {
    function run() external {
        address gameContract = 0xe9cFc825a66780651A7844f470E70DfdbabC9636;
        ChainAssassin game = ChainAssassin(gameContract);

        // --- Game parameters ---
        IChainAssassin.CreateGameParams memory params = IChainAssassin.CreateGameParams({
            title: "Chapultepec Hunt",
            entryFee: 0.001 ether,
            minPlayers: 3,
            maxPlayers: 20,
            registrationDeadline: uint40(block.timestamp + 7 days),
            gameDate: uint40(block.timestamp + 8 days),
            maxDuration: 7200, // 2 hours
            centerLat: 19420456,   // 19.420456° (Chapultepec Park)
            centerLng: -99189554,  // -99.189554°
            bps1st: 3500,
            bps2nd: 1500,
            bps3rd: 1000,
            bpsKills: 2000,
            bpsCreator: 1000
            // platform gets remaining 1000 (10%)
        });

        // --- Zone shrink schedule ---
        IChainAssassin.ZoneShrink[] memory shrinks = new IChainAssassin.ZoneShrink[](4);
        shrinks[0] = IChainAssassin.ZoneShrink({atSecond: 0,    radiusMeters: 500});
        shrinks[1] = IChainAssassin.ZoneShrink({atSecond: 1800, radiusMeters: 300});
        shrinks[2] = IChainAssassin.ZoneShrink({atSecond: 3600, radiusMeters: 150});
        shrinks[3] = IChainAssassin.ZoneShrink({atSecond: 5400, radiusMeters: 50});

        console.log("Creating game on contract:", gameContract);
        console.log("Title: Chapultepec Hunt");
        console.log("Entry fee: 0.001 ETH");
        console.log("Registration deadline:", uint256(params.registrationDeadline));
        console.log("Game date:", uint256(params.gameDate));

        vm.startBroadcast();
        game.createGame(params, shrinks);
        vm.stopBroadcast();

        uint256 nextId = game.nextGameId();
        console.log("Game created! Next game ID:", nextId);
    }
}
