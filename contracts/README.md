# Chain Assassin Smart Contracts

Solidity smart contracts for the Chain Assassin game on Base (Ethereum L2).

## Architecture

Single `ChainAssassin.sol` contract manages all games via game ID mappings. The contract serves as an **escrow + settlement layer** — the backend handles real-time game logic (GPS, QR scanning, target assignment), while the contract handles money (entry fees, prizes, refunds).

### On-Chain
- Registration + entry fee escrow
- Prize distribution + claiming (pull pattern)
- Cancellation + refunds
- Platform fee collection
- Kill/elimination recording

### Off-Chain (Backend)
- GPS location validation
- QR code scan verification
- Target assignment
- Zone shrink enforcement
- Check-in verification

## Prize Distribution

Configurable per game via basis points (must sum to 10000):
- **1st Place** — e.g., 40%
- **2nd Place** — e.g., 15%
- **3rd Place** — e.g., 10%
- **Most Kills** — e.g., 25%
- **Platform Fee** — e.g., 10% (can be 0)

## Safety Mechanisms

- `triggerCancellation()` — anyone can cancel if registration deadline passes with too few players
- `triggerExpiry()` — anyone can cancel if game is stuck in ACTIVE past expiry deadline
- Both use pull pattern — players claim refunds individually

## Build

```bash
forge build
```

## Test

```bash
forge test -vvv
```

## Deploy

```bash
# Base Sepolia (testnet)
forge script script/Deploy.s.sol \
  --rpc-url https://sepolia.base.org \
  --private-key $PRIVATE_KEY \
  --broadcast \
  --verify \
  --verifier etherscan \
  --etherscan-api-key $ETHERSCAN_API_KEY \
  --verifier-url "https://api.etherscan.io/v2/api?chainid=84532"

# Base Mainnet
forge script script/Deploy.s.sol \
  --rpc-url $BASE_RPC \
  --private-key $PRIVATE_KEY \
  --broadcast \
  --verify \
  --verifier etherscan \
  --etherscan-api-key $ETHERSCAN_API_KEY \
  --verifier-url "https://api.etherscan.io/v2/api?chainid=8453"
```

## Deployments

| Network | Address | Explorer |
|---------|---------|----------|
| Base Sepolia | `0xe9cFc825a66780651A7844f470E70DfdbabC9636` | [BaseScan](https://sepolia.basescan.org/address/0xe9cFc825a66780651A7844f470E70DfdbabC9636) |

## Dependencies

- [OpenZeppelin Contracts](https://github.com/OpenZeppelin/openzeppelin-contracts) — Ownable, ReentrancyGuard
- [Forge Std](https://github.com/foundry-rs/forge-std) — Testing framework
