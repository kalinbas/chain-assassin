// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console} from "forge-std/Script.sol";
import {ChainAssassin} from "../src/ChainAssassin.sol";
import {IChainAssassin} from "../src/IChainAssassin.sol";

/// @title RegisterPlayer — Registers the broadcaster for a game
/// @notice Sends the exact entry fee to register for the specified game.
contract RegisterPlayer is Script {
    function run() external {
        // --- Config ---
        address gameContract = 0x0ABfD376Bd339A6dcd885F37aB0A9cE761c2F99e;
        uint256 gameId = 1;

        ChainAssassin game = ChainAssassin(gameContract);

        // Read entry fee from the contract
        IChainAssassin.GameConfig memory config = game.getGameConfig(gameId);

        console.log("Game ID:", gameId);
        console.log("Game:", config.title);
        console.log("Entry fee (wei):", uint256(config.entryFee));
        console.log("Player:", msg.sender);

        vm.startBroadcast();
        game.register{value: config.entryFee}(gameId);
        vm.stopBroadcast();

        console.log("Successfully registered!");
    }
}
