# Chain Assassin Game Server

Off-chain game server for **Chain Assassin** — a blockchain-based real-world elimination game. Players hunt assigned targets in a shrinking zone using GPS and BLE proximity, with kills and prizes settled on-chain.

The server orchestrates game logic (target chains, zone tracking, kill verification) while the [smart contract](../contracts/) handles registration, prize pools, and payouts.

## Tech Stack

| Component | Library |
|-----------|---------|
| Runtime | Node.js 22 + TypeScript (ESM) |
| Blockchain | ethers.js v6 |
| Database | SQLite (better-sqlite3) |
| WebSocket | ws |
| HTTP API | Express |
| Logging | pino |
| Testing | vitest + E2E on Anvil |

## Quick Start

```bash
# Install dependencies
npm install

# Configure environment
cp .env.example .env
# Edit .env with your contract address, operator key, and RPC URLs

# Development (hot reload)
npm run dev

# Production
npm run build
npm start
```

## Environment Variables

```bash
# Blockchain
RPC_URL=https://sepolia.base.org       # HTTP JSON-RPC endpoint
RPC_WS_URL=wss://sepolia.base.org      # WebSocket RPC (for event listening)
CONTRACT_ADDRESS=0x...                  # Deployed ChainAssassin contract
OPERATOR_PRIVATE_KEY=0x...              # Server operator wallet (submits txs)
CHAIN_ID=84532                          # Chain ID (default: Base Sepolia)

# Server
PORT=3000
HOST=0.0.0.0
ENABLE_SIMULATION_API=false             # Enable debug simulation endpoints

# Database
DB_PATH=./data/chain-assassin.db       # SQLite file path

# Game Settings
KILL_PROXIMITY_METERS=100              # Max GPS distance for kills
ZONE_GRACE_SECONDS=60                  # Seconds before zone elimination
GPS_PING_INTERVAL_SECONDS=5            # Expected client ping interval
BLE_REQUIRED=true                      # Require Bluetooth proximity for kills

# Heartbeat
HEARTBEAT_INTERVAL_SECONDS=600         # Seconds between required heartbeat scans
HEARTBEAT_PROXIMITY_METERS=100         # Max distance from scanned heartbeat QR target
HEARTBEAT_DISABLE_THRESHOLD=4          # Disable heartbeat when alive players <= threshold

# Check-in / pregame
CHECKIN_DURATION_SECONDS=300           # Check-in window duration after registration closes
PREGAME_DURATION_SECONDS=180           # Pregame countdown before hunt starts

# Photos
PHOTOS_DIR=./data/photos               # Upload destination for evidence photos
MAX_PHOTO_SIZE_MB=5                    # Max image payload size

# Sync / startup
START_GAME_ID=1                        # Ignore chain games below this ID
REBUILD_DB=false                       # Wipe DB and rebuild from chain on startup

# WS listener resilience
WS_HEARTBEAT_CHECK_INTERVAL_MS=30000   # How often to check listener heartbeat
WS_HEARTBEAT_STALE_MS=120000           # Restart listener if heartbeat older than this
WS_RESTART_COOLDOWN_MS=30000           # Minimum gap between restart attempts

# Logging
LOG_LEVEL=info                         # pino log level
```

## REST API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health` | No | Health check |
| `GET` | `/api/games/:gameId/status` | No | Game state, leaderboard, zone info |
| `POST` | `/api/games/:gameId/kill` | Wallet | Submit kill proof (QR + GPS + BLE) |
| `POST` | `/api/games/:gameId/location` | Wallet | GPS ping (REST fallback) |
| `POST` | `/api/games/:gameId/checkin` | Wallet | Pre-game check-in (GPS + optional QR) |
| `POST` | `/api/admin/check-auto-start` | Operator | Trigger deadline check on demand |

**Authentication:** Wallet-signed headers for authenticated routes:
- `X-Address` — wallet address
- `X-Signature` — EIP-191 signature
- `X-Message` — signed message (`chain-assassin:{timestamp}`)

## WebSocket Protocol

**Endpoint:** `ws://{host}:{port}/ws`

### Client -> Server

| Type | Payload | Purpose |
|------|---------|---------|
| `auth` | gameId, address, signature, message | Authenticate (first message) |
| `location` | lat, lng, timestamp | GPS ping |
| `ble_proximity` | nearbyAddresses[] | BLE scan results |

### Server -> Client

| Type | Payload | Purpose |
|------|---------|---------|
| `auth:success` | gameId + state snapshot | Auth confirmed |
| `game:started` | target info, zone state | Game activated |
| `game:ended` | winner1, winner2, winner3, topKiller | Game over |
| `game:cancelled` | gameId | Game cancelled |
| `kill:recorded` | hunter, target, hunterKills | Kill event (broadcast) |
| `target:assigned` | target address + number | New target assignment |
| `zone:shrink` | newRadius, nextShrinkAt | Zone changed |
| `zone:warning` | secondsRemaining | Out-of-zone warning |
| `player:eliminated` | player, eliminator, reason | Elimination event |
| `leaderboard:update` | entries[] | Rankings update |
| `checkin:update` | checkedInCount, totalPlayers | Check-in progress |
| `error` | message | Error |

## Architecture

```
server/
├── abi/                        # Contract ABI (from contracts/out/)
├── data/                       # SQLite database (runtime)
├── test/                       # E2E tests
└── src/
    ├── index.ts                # Entry point + graceful shutdown
    ├── config.ts               # Environment variable loader
    ├── api/
    │   ├── routes.ts           # Express router
    │   ├── middleware.ts       # EIP-191 wallet signature auth
    │   └── handlers.ts        # Request handlers
    ├── blockchain/
    │   ├── client.ts           # ethers providers + operator wallet
    │   ├── contract.ts         # Contract instances + read helpers
    │   ├── listener.ts         # On-chain event listener + backfill
    │   └── operator.ts         # Operator tx queue (nonce-safe)
    ├── game/
    │   ├── manager.ts          # Game lifecycle orchestration
    │   ├── targetChain.ts      # Circular target chain algorithm
    │   ├── zoneTracker.ts      # Zone shrink + out-of-zone tracking
    │   ├── killVerifier.ts     # Kill verification (QR + GPS + BLE)
    │   └── leaderboard.ts     # Winner determination
    ├── ws/
    │   ├── server.ts           # WebSocket server + auth
    │   ├── handlers.ts        # Incoming message handlers
    │   └── rooms.ts           # Game room management + broadcast
    ├── db/
    │   ├── schema.ts           # CREATE TABLE statements
    │   ├── migrations.ts      # Schema migration runner
    │   └── queries.ts         # Prepared statement wrappers
    └── utils/
        ├── geo.ts              # Haversine distance, zone math
        ├── crypto.ts          # EIP-191 signature verification
        ├── logger.ts          # pino logger factory
        └── types.ts           # Shared TypeScript types
```

## Game Lifecycle

```
REGISTRATION ──→ ACTIVE ──→ ENDED
      │
      └──→ CANCELLED (if minPlayers not met by deadline)
```

1. **Game created** on-chain → server indexes config + zone shrinks, schedules deadline timer
2. **Players register** on-chain → server tracks each player
3. **Players check in** at the venue — first 5% (min 1) via GPS only, rest must scan a checked-in player's QR (viral)
4. **Registration deadline** fires → server calls `startGame()` (if minPlayers met) or game gets cancelled
5. **Game active** → target chain initialized, 1s game tick starts, zone enforcement begins
6. **Players submit kills** → server verifies (QR + GPS + BLE), records on-chain, updates target chain
7. **Last player standing** → server calls `endGame()` with winners, prizes claimable on-chain

### Target Chain

Circular linked list — each player hunts exactly one other, forming a cycle.

```
Initial:    A → B → C → D → A
A kills B:  A → C → D → A        (A inherits B's target)
D out of zone: A → C → A         (C's target reassigned)
A kills C:  A wins
```

### Kill Verification

1. Parse QR payload (obfuscated numeric — multiplicative cipher over `gameId * 10000 + playerNumber`)
2. Resolve player number to wallet address, verify it's hunter's assigned target
3. Verify GPS proximity (Haversine distance ≤ configured threshold)
4. Verify BLE proximity (target in hunter's nearby addresses)
5. Record kill on-chain via operator tx
6. Update target chain + broadcast events

### Zone Enforcement

- Players send GPS pings every 5s
- Server checks distance to zone center (Haversine)
- Outside zone → 60s countdown starts
- Back in zone → countdown resets
- Countdown expires → player eliminated on-chain
- Zone shrinks on a schedule defined at game creation

## Blockchain Integration

The server listens for on-chain events via WebSocket provider:

| Event | Action |
|-------|--------|
| `GameCreated` | Index game config, schedule deadline timer |
| `PlayerRegistered` | Track player in DB |
| `GameStarted` | Initialize target chain, start zone tracker |
| `KillRecorded` | Confirmation logging |
| `PlayerEliminated` | Confirmation logging |
| `GameEnded` | Cleanup active game state |
| `GameCancelled` | Cleanup, broadcast to players |

**Auto-start:** Per-game `setTimeout` scheduled at exact registration deadline (no polling). When the timer fires, the server checks chain time and starts the game if minPlayers met.

**Nonce management:** Sequential operator tx queue prevents nonce collisions across concurrent games.

**Startup recovery:** On boot, recovers ACTIVE games (target chains, zone trackers, tick intervals) and REGISTRATION games (deadline timers) from SQLite. Backfills missed events from last processed block.

## Simulation

The server can deploy and run fully simulated games on-chain. One API call creates a game, registers simulated players, and lets the normal server lifecycle handle auto-start, check-in, pregame, and gameplay.

### Deploy a simulated game

```bash
curl -X POST http://localhost:3000/api/simulation/deploy \
  -H "Content-Type: application/json" \
  -d '{
    "playerCount": 10,
    "centerLat": 19.4357,
    "centerLng": -99.1299,
    "initialRadiusMeters": 500,
    "speedMultiplier": 1,
    "title": "Test Game",
    "entryFeeWei": "0",
    "registrationDelaySeconds": 120
  }'
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `playerCount` | 10 | Number of simulated players (3-50) |
| `centerLat` | 19.4357 | Game zone center latitude |
| `centerLng` | -99.1299 | Game zone center longitude |
| `initialRadiusMeters` | 500 | Initial zone radius |
| `speedMultiplier` | 1 | Simulation speed (1 = real-time, up to 50) |
| `title` | "Simulation Game" | Game title |
| `entryFeeWei` | "0" | Entry fee in wei ("0" for free) |
| `registrationDelaySeconds` | 120 | Seconds until registration closes and game auto-starts (30-600) |

**Flow:** Game created on-chain → simulated wallets funded & registered on-chain → registration deadline fires → `startGame` on-chain → simulated players auto-check-in → pregame countdown → gameplay simulation (movement, kills, heartbeats, zone shrinks) → `endGame` on-chain.

Real players can join the game during registration via the Android app.

### Check status / Stop

```bash
# Status
curl http://localhost:3000/api/simulation/status

# Stop
curl -X POST http://localhost:3000/api/simulation/stop
```

## Deployment (Fly.io)

The server runs on [Fly.io](https://fly.io) at **https://chain-assassin.fly.dev**.

### Prerequisites

- [Fly CLI](https://fly.io/docs/flyctl/install/) (`brew install flyctl`)
- Docker (for local image builds: `brew install --cask docker`)

### First-time setup

```bash
cd server

fly auth login
fly apps create chain-assassin
fly volumes create game_data --region iad --size 1

fly secrets set \
  RPC_URL="https://base-sepolia.g.alchemy.com/v2/YOUR_KEY" \
  RPC_WS_URL="wss://base-sepolia.g.alchemy.com/v2/YOUR_KEY" \
  CONTRACT_ADDRESS="0x..." \
  OPERATOR_PRIVATE_KEY="0x..."
```

### Deploy

```bash
fly deploy --local-only
```

### Monitoring

```bash
fly logs              # Live logs
fly status            # Machine status
curl https://chain-assassin.fly.dev/health
```

### Infrastructure

- **Region:** `iad` (Virginia)
- **VM:** shared-cpu-1x, 512MB RAM
- **Storage:** 1GB persistent volume at `/app/data` (SQLite + photos)
- **Always-on:** Machine runs 24/7 for real-time blockchain event listening
- **TLS:** Automatic via Fly.io proxy

## Docker (local)

```bash
# Build and run
docker compose up --build

# The compose file includes:
# - Persistent volume for SQLite data
# - Health check on /health endpoint
# - Restart policy: unless-stopped
```

## Testing

```bash
# Unit tests
npm test

# End-to-end test (requires Foundry/Anvil installed)
npm run test:e2e
```

The E2E test spins up a local Anvil chain, deploys the contract, starts the server, and runs 3 scenarios with 164 assertions:

- **Scenario A:** Full 6-player game — registration, check-in, auto-start, WebSocket connections, kill rejections, 5 kills across multiple hunters, prize claims, full database verification
- **Scenario B:** Game cancellation + refund — insufficient players, cancellation, refund claims, revert tests
- **Scenario C:** Platform fee withdrawal — accrual verification, withdrawal, double-withdrawal revert

## Scripts

| Script | Description |
|--------|-------------|
| `npm run dev` | Development server with hot reload (tsx) |
| `npm run build` | Compile TypeScript to `dist/` |
| `npm start` | Run compiled server |
| `npm test` | Run unit tests (vitest) |
| `npm run test:e2e` | Run full E2E test suite on Anvil |
| `npm run lint` | Lint with ESLint |
