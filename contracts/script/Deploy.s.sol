// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "forge-std/Script.sol";
import "../src/ChainAssassin.sol";

contract DeployChainAssassin is Script {
    function run() external {
        vm.startBroadcast();

        ChainAssassin game = new ChainAssassin();

        // Add deployer as operator
        game.addOperator(msg.sender);

        vm.stopBroadcast();

        console.log("ChainAssassin deployed at:", address(game));
        console.log("Owner:", msg.sender);
        console.log("Operator:", msg.sender);
    }
}
