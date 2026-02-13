#!/bin/bash
set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Chain Assassin — E2E Test Runner${NC}"
echo -e "${CYAN}============================================${NC}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"
CONTRACTS_DIR="$(dirname "$SERVER_DIR")/contracts"
RPC_URL="http://127.0.0.1:8545"
RPC_WS_URL="ws://127.0.0.1:8545"

# Test database path
E2E_DB_PATH="$SERVER_DIR/data/e2e-test.db"

# Cleanup function
cleanup() {
  echo -e "\n${CYAN}Cleaning up...${NC}"
  if [ -n "$ANVIL_PID" ]; then
    kill $ANVIL_PID 2>/dev/null || true
    echo "  Stopped Anvil (PID $ANVIL_PID)"
  fi
  if [ -n "$SERVER_PID" ]; then
    kill $SERVER_PID 2>/dev/null || true
    echo "  Stopped Server (PID $SERVER_PID)"
  fi
  # Clean up test database and WAL/SHM files
  rm -f "$E2E_DB_PATH" "$E2E_DB_PATH-wal" "$E2E_DB_PATH-shm"
  echo "  Cleaned up test database"
  # Clean up .env.test
  rm -f "$SERVER_DIR/.env.test"
}
trap cleanup EXIT

# ============ Step 1: Start Anvil ============
echo -e "\n${CYAN}[1/5] Starting Anvil...${NC}"

# Check if Anvil is available
if ! command -v anvil &> /dev/null; then
  echo -e "${RED}Error: anvil not found. Install Foundry: https://book.getfoundry.sh/getting-started/installation${NC}"
  exit 1
fi

# Kill any existing Anvil on port 8545
lsof -ti:8545 | xargs kill 2>/dev/null || true
sleep 1

anvil --silent &
ANVIL_PID=$!
echo "  Anvil started (PID $ANVIL_PID)"

# Wait for Anvil to be ready
for i in $(seq 1 10); do
  if curl -s $RPC_URL -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' > /dev/null 2>&1; then
    echo -e "  ${GREEN}Anvil ready${NC}"
    break
  fi
  sleep 0.5
done

# ============ Step 2: Deploy Contract ============
echo -e "\n${CYAN}[2/5] Deploying contract...${NC}"

cd "$CONTRACTS_DIR"

# Anvil account 0 private key
DEPLOYER_KEY="0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"

# Deploy using forge script
DEPLOY_OUTPUT=$(forge script script/Deploy.s.sol \
  --rpc-url $RPC_URL \
  --private-key $DEPLOYER_KEY \
  --broadcast 2>&1)

# Extract contract address from output
CONTRACT_ADDRESS=$(echo "$DEPLOY_OUTPUT" | grep "ChainAssassin deployed at:" | awk '{print $NF}')

if [ -z "$CONTRACT_ADDRESS" ]; then
  echo -e "${RED}Failed to extract contract address from deploy output:${NC}"
  echo "$DEPLOY_OUTPUT"
  exit 1
fi

echo -e "  ${GREEN}Contract deployed at: $CONTRACT_ADDRESS${NC}"

# ============ Step 3: Start Server ============
echo -e "\n${CYAN}[3/5] Starting game server...${NC}"

cd "$SERVER_DIR"

# Delete stale test database before starting server
rm -f "$E2E_DB_PATH" "$E2E_DB_PATH-wal" "$E2E_DB_PATH-shm"

# Start the server with test env vars
DB_PATH="$E2E_DB_PATH" \
  RPC_URL=$RPC_URL \
  RPC_WS_URL=$RPC_WS_URL \
  CONTRACT_ADDRESS=$CONTRACT_ADDRESS \
  OPERATOR_PRIVATE_KEY=$DEPLOYER_KEY \
  CHAIN_ID=31337 \
  PORT=3000 \
  HOST=0.0.0.0 \
  POLLING_INTERVAL_MS=1000 \
  CHECKIN_DURATION_SECONDS=30 \
  PREGAME_DURATION_SECONDS=5 \
  KILL_PROXIMITY_METERS=500 \
  ZONE_GRACE_SECONDS=60 \
  BLE_REQUIRED=true \
  LOG_LEVEL=warn \
  npx tsx src/index.ts &
SERVER_PID=$!
echo "  Server started (PID $SERVER_PID)"

# Wait for server to be ready
echo "  Waiting for server to be ready..."
for i in $(seq 1 20); do
  if curl -s http://127.0.0.1:3000/health > /dev/null 2>&1; then
    echo -e "  ${GREEN}Server ready${NC}"
    break
  fi
  if [ $i -eq 20 ]; then
    echo -e "${RED}Server failed to start within 20s${NC}"
    exit 1
  fi
  sleep 1
done

# ============ Step 4: Run E2E Test ============
echo -e "\n${CYAN}[4/5] Running E2E test...${NC}\n"

cd "$SERVER_DIR"
npx tsx test/e2e.ts "$CONTRACT_ADDRESS"
TEST_EXIT=$?

# ============ Step 5: Results ============
echo -e "\n${CYAN}[5/5] Results${NC}"

if [ $TEST_EXIT -eq 0 ]; then
  echo -e "${GREEN}All E2E tests passed!${NC}"
else
  echo -e "${RED}E2E tests failed (exit code: $TEST_EXIT)${NC}"
fi

exit $TEST_EXIT
