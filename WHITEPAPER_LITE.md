# Chain Assassin Litepaper

Version 1.0  
Date: February 13, 2026

## TL;DR

Chain Assassin is a real-world elimination game with crypto-native settlement rails.

- Players register with ETH on Base (Arbitrum support planned).
- Gameplay is enforced off-chain (GPS, BLE, QR logic) for real-time speed.
- Funds are escrowed and settled on-chain for transparency and claim safety.
- The full stack is open-source and rules are publicly auditable.

## Why This Exists

Most prize-based real-world games are opaque and custodial. Chain Assassin replaces opaque custody with on-chain escrow and deterministic payout/refund flows.

## Core Product

1. Join game with wallet + entry fee.
2. Check in physically and survive a shrinking zone.
3. Eliminate assigned targets by validated scans.
4. Claim prize (or refund if cancelled) directly from contract.

## Open-source + Moderatorless Execution

Chain Assassin is built for no-live-moderator operation.

- No human referee is required to manually run a match.
- Once a game is crowdfunded (meets configured participation/escrow thresholds), progression is automatic.
- Timeline gates (registration/check-in/pregame/active/settlement) are enforced by deterministic server + contract rules.

## Architecture in One Line

Fast game loop off-chain, hard money guarantees on-chain.

## What Is On-Chain

- Game configuration and phase state.
- Entry fee escrow and prize/refund claimability.
- Fee accounting (platform + creator).
- Permissionless cancellation/expiry paths.

## What Is Off-Chain

- Check-in orchestration.
- Target assignment/reassignment.
- Kill verification (QR + GPS + BLE).
- Zone tracking and elimination rules.
- Real-time WebSocket state fan-out.

## Security and Trust

Chain Assassin is trust-minimized, not fully trustless gameplay.

Trusted:

- server anti-cheat logic,
- operator role for writing outcomes.

Moderator model:

- no discretionary live moderator actions are required during normal gameplay.

Untrusted/permissionless guarantees:

- contract-held funds,
- deterministic payout math,
- user-initiated claims,
- permissionless cancellation triggers after deadline/expiry conditions.

## Economic Model

Per-game escrow = base reward + entry fees.

BPS split supports:

- 1st / 2nd / 3rd place,
- most kills,
- creator fee,
- platform fee.

No native token is required. ETH on Base is the settlement unit today (Arbitrum support planned).

## User Surface Split

Mobile app:

- wallet operations,
- registration tx and confirmations,
- live gameplay actions.

Website:

- game discovery,
- spectator live page,
- historical game pages,
- social sharing funnel.

## Why It Matters

Chain Assassin is a reusable pattern for crypto-native physical competition:

- transparent escrow,
- verifiable settlement,
- practical real-time game UX.

## Roadmap Summary

1. Reliability hardening and deeper simulation/e2e coverage.
2. Organizer tooling and anti-abuse analytics.
3. Progressive decentralization (multi-operator governance + attestations).

## Current Deployment

- Network: Base Sepolia (Arbitrum support planned)
- Contract: `0x991a415B644E84A8a1F12944C6817bf2117e18D7`
- Explorer: <https://sepolia.basescan.org/address/0x991a415B644E84A8a1F12944C6817bf2117e18D7>
