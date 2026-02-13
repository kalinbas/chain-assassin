# CHAIN ASSASSIN

**Hunt or be hunted. On-chain.**

Real-world elimination game with GPS-tracked zones, QR-code kills, and ETH prizes on [Base](https://base.org) blockchain.

## How It Works

1. **Create Wallet** — Generate a Base wallet in-app and deposit ETH
2. **Join a Game** — Browse upcoming games by city, pay the entry fee
3. **Check In** — Arrive at the game zone, scan another player's QR to verify attendance
4. **Hunt Your Target** — Find and scan your target's QR code while staying inside the shrinking zone
5. **Win ETH** — Top 3 players split the prize pool (40% / 15% / 10%)

## Game Mechanics

- **Target Assignment** — Each player gets a random target. Scan their QR code to eliminate them. Get a new target after each kill.
- **Shrinking Zone** — Play zone starts at 2km radius and shrinks in stages. 60 seconds outside = elimination.
- **Tactical Items** — One-use abilities: Ping Target, Ping Hunter, Ghost Mode, Decoy Ping, EMP Blast
- **Prize Pool** — 90% of entry fees distributed to winners. 10% platform fee. Full refund if game is cancelled.

## Monorepo Structure

```
chain-assassin/
├── app-android/     Android app (Kotlin + Jetpack Compose)
├── contracts/       Smart contracts (Solidity + Foundry)
├── server/          Game server (Node.js + TypeScript)
├── website/         Web app (React + TypeScript + Vite)
└── scripts/         Dev and setup scripts
```

### `app-android/`

Android prototype built with:
- Kotlin + Jetpack Compose + Material 3
- Hilt dependency injection
- Web3j for wallet keypair generation (Base/Ethereum)
- CameraX + ML Kit for QR scanning
- GPS location tracking with foreground service
- osmdroid (OpenStreetMap) for game map

**Build:**
```bash
cd app-android
cp local.properties.example local.properties  # add your MAPS_API_KEY
./gradlew assembleDebug
```

Requirements: Android SDK 34, Min SDK 29, Gradle 8.5

### `website/`

Web app built with:
- React 19 + TypeScript + Vite
- React Router for routes
- Leaflet + React Leaflet for maps
- API-first integration with the game server (no direct blockchain calls)

### `server/`

Game coordination server with:
- WebSocket connections for real-time game state
- REST API for game management
- SQLite persistence and startup recovery
- Smart contract event indexing + operator transactions on Base

### `contracts/`

Smart contracts built with:
- Solidity 0.8.24
- Foundry (forge/anvil/cast)
- OpenZeppelin security primitives (Ownable, ReentrancyGuard)

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Smart Contracts | Solidity 0.8.24, Foundry, OpenZeppelin |
| Blockchain Network | Base (Ethereum L2): Base Sepolia + Base Mainnet |
| Backend Server | Node.js 22, TypeScript, Express, ws, ethers v6, better-sqlite3 |
| Backend Testing | Vitest (unit/integration), E2E flows on Anvil |
| Web App | React 19, TypeScript, Vite, React Router, Leaflet/React Leaflet |
| Web Testing | Playwright |
| Android App | Kotlin, Jetpack Compose, Material 3, Hilt, Web3j, CameraX, ML Kit, osmdroid |
| CI/Tooling | npm, Gradle, Foundry, TypeScript |

## Links

- [Website](https://chainassassin.xyz/)
- [Twitter / X](https://x.com/assassin_chain)
- [Discord](https://discord.gg/SayMP2cJsp)

## License

All rights reserved. &copy; 2026 Chain Assassin.
