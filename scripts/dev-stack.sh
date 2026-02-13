#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACTS_DIR="$ROOT_DIR/contracts"
SERVER_DIR="$ROOT_DIR/server"
WEBSITE_DIR="$ROOT_DIR/website"
RUNTIME_DIR="$ROOT_DIR/.dev-stack"

ANVIL_PORT="${ANVIL_PORT:-8545}"
SERVER_PORT="${SERVER_PORT:-3000}"
WEB_PORT="${WEB_PORT:-5173}"

RPC_URL="http://127.0.0.1:${ANVIL_PORT}"
RPC_WS_URL="ws://127.0.0.1:${ANVIL_PORT}"
SERVER_URL="http://127.0.0.1:${SERVER_PORT}"
SERVER_WS_URL="ws://127.0.0.1:${SERVER_PORT}"
WEB_URL="http://127.0.0.1:${WEB_PORT}"

DEPLOYER_KEY="0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
DB_PATH="$SERVER_DIR/data/dev-stack.db"

ANVIL_LOG="$RUNTIME_DIR/anvil.log"
SERVER_LOG="$RUNTIME_DIR/server.log"
WEBSITE_LOG="$RUNTIME_DIR/website.log"
RUNTIME_ENV="$RUNTIME_DIR/runtime.env"

ANVIL_PID=""
SERVER_PID=""
WEBSITE_PID=""

mkdir -p "$RUNTIME_DIR" "$SERVER_DIR/data"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

wait_for_rpc() {
  local url="$1"
  local tries=40
  for _ in $(seq 1 "$tries"); do
    if curl -s "$url" \
      -X POST \
      -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

wait_for_http() {
  local url="$1"
  local tries=60
  for _ in $(seq 1 "$tries"); do
    if curl -s "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

ensure_deps() {
  local dir="$1"
  if [ ! -d "$dir/node_modules" ]; then
    echo "Installing dependencies in $dir ..."
    npm --prefix "$dir" install
  fi
}

kill_on_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti tcp:"$port" || true)"
  if [ -n "$pids" ]; then
    echo "Freeing port $port (stopping: $pids)"
    # shellcheck disable=SC2086
    kill $pids >/dev/null 2>&1 || true
    sleep 0.5
  fi
}

cleanup() {
  set +e
  echo ""
  echo "Shutting down local dev stack..."
  if [ -n "$WEBSITE_PID" ] && kill -0 "$WEBSITE_PID" >/dev/null 2>&1; then
    kill "$WEBSITE_PID" >/dev/null 2>&1 || true
    echo "  stopped website ($WEBSITE_PID)"
  fi
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    echo "  stopped server ($SERVER_PID)"
  fi
  if [ -n "$ANVIL_PID" ] && kill -0 "$ANVIL_PID" >/dev/null 2>&1; then
    kill "$ANVIL_PID" >/dev/null 2>&1 || true
    echo "  stopped anvil ($ANVIL_PID)"
  fi
}

trap cleanup EXIT INT TERM

require_cmd anvil
require_cmd forge
require_cmd node
require_cmd npm
require_cmd curl
require_cmd lsof

ensure_deps "$SERVER_DIR"
ensure_deps "$WEBSITE_DIR"

kill_on_port "$ANVIL_PORT"
kill_on_port "$SERVER_PORT"
kill_on_port "$WEB_PORT"

rm -f "$ANVIL_LOG" "$SERVER_LOG" "$WEBSITE_LOG" "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm"

echo "Starting Anvil on :$ANVIL_PORT ..."
anvil --silent --port "$ANVIL_PORT" >"$ANVIL_LOG" 2>&1 &
ANVIL_PID="$!"

if ! wait_for_rpc "$RPC_URL"; then
  echo "Anvil failed to start. See $ANVIL_LOG" >&2
  exit 1
fi

echo "Deploying contract ..."
DEPLOY_OUTPUT="$(
  cd "$CONTRACTS_DIR" && \
    forge script script/Deploy.s.sol \
      --rpc-url "$RPC_URL" \
      --private-key "$DEPLOYER_KEY" \
      --broadcast 2>&1
)"

CONTRACT_ADDRESS="$(echo "$DEPLOY_OUTPUT" | grep "ChainAssassin deployed at:" | tail -n 1 | awk '{print $NF}')"
if [ -z "${CONTRACT_ADDRESS:-}" ]; then
  echo "Failed to parse contract address from deployment output" >&2
  echo "$DEPLOY_OUTPUT" >&2
  exit 1
fi

echo "Starting server on :$SERVER_PORT ..."
(
  cd "$SERVER_DIR"
  DB_PATH="$DB_PATH" \
  RPC_URL="$RPC_URL" \
  RPC_WS_URL="$RPC_WS_URL" \
  CONTRACT_ADDRESS="$CONTRACT_ADDRESS" \
  OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY" \
  CHAIN_ID=31337 \
  PORT="$SERVER_PORT" \
  HOST=0.0.0.0 \
  ENABLE_SIMULATION_API=true \
  LOG_LEVEL=info \
  npx tsx src/index.ts
) >"$SERVER_LOG" 2>&1 &
SERVER_PID="$!"

if ! wait_for_http "$SERVER_URL/health"; then
  echo "Server failed to start. See $SERVER_LOG" >&2
  exit 1
fi

echo "Seeding sample games ..."
SEED_OUTPUT="$(
  cd "$SERVER_DIR" && \
    RPC_URL="$RPC_URL" \
    CONTRACT_ADDRESS="$CONTRACT_ADDRESS" \
    OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY" \
    node scripts/seed-sample-games.mjs
)"
echo "$SEED_OUTPUT"

cat >"$WEBSITE_DIR/.env.local" <<EOF
VITE_SERVER_URL=${SERVER_URL}
VITE_SERVER_WS_URL=${SERVER_WS_URL}
EOF

echo "Starting website on :$WEB_PORT ..."
(
  cd "$WEBSITE_DIR"
  npm run dev -- --host 0.0.0.0 --port "$WEB_PORT"
) >"$WEBSITE_LOG" 2>&1 &
WEBSITE_PID="$!"

if ! wait_for_http "$WEB_URL"; then
  echo "Website failed to start. See $WEBSITE_LOG" >&2
  exit 1
fi

cat >"$RUNTIME_ENV" <<EOF
ANVIL_PID=${ANVIL_PID}
SERVER_PID=${SERVER_PID}
WEBSITE_PID=${WEBSITE_PID}
ANVIL_PORT=${ANVIL_PORT}
SERVER_PORT=${SERVER_PORT}
WEB_PORT=${WEB_PORT}
RPC_URL=${RPC_URL}
RPC_WS_URL=${RPC_WS_URL}
CONTRACT_ADDRESS=${CONTRACT_ADDRESS}
DB_PATH=${DB_PATH}
EOF

echo ""
echo "Local dev stack is ready."
echo "  RPC:       $RPC_URL"
echo "  Contract:  $CONTRACT_ADDRESS"
echo "  Server:    $SERVER_URL"
echo "  Website:   $WEB_URL"
echo "  DB:        $DB_PATH"
echo ""
echo "Logs:"
echo "  Anvil:     $ANVIL_LOG"
echo "  Server:    $SERVER_LOG"
echo "  Website:   $WEBSITE_LOG"
echo ""
echo "Runtime env: $RUNTIME_ENV"
echo "Press Ctrl+C to stop everything."

while true; do
  if ! kill -0 "$ANVIL_PID" >/dev/null 2>&1; then
    echo "Anvil exited unexpectedly. Check $ANVIL_LOG" >&2
    exit 1
  fi
  if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    echo "Server exited unexpectedly. Check $SERVER_LOG" >&2
    exit 1
  fi
  if ! kill -0 "$WEBSITE_PID" >/dev/null 2>&1; then
    echo "Website exited unexpectedly. Check $WEBSITE_LOG" >&2
    exit 1
  fi
  sleep 2
done
