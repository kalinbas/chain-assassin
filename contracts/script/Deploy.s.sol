// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console} from "forge-std/Script.sol";
import {ChainAssassin} from "../src/ChainAssassin.sol";

/// @title DeployChainAssassin — Foundry deployment script
/// @notice Deploys ChainAssassin and adds the deployer as the first operator.
contract DeployChainAssassin is Script {
    function run() external {
        vm.startBroadcast();

        uint16 platformFeeBps = 1000; // 10% platform fee
        ChainAssassin game = new ChainAssassin(platformFeeBps);

        // Add deployer as operator
        game.addOperator(msg.sender);

        vm.stopBroadcast();

        console.log("ChainAssassin deployed at:", address(game));
        console.log("Owner:", msg.sender);
        console.log("Operator:", msg.sender);
    }
}
