#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACTS_DIR="$ROOT_DIR/contracts"
SERVER_DIR="$ROOT_DIR/server"
RUNTIME_DIR="$ROOT_DIR/.dev-stack/app-e2e"

ANVIL_PORT="${ANVIL_PORT:-8545}"
SERVER_PORT="${SERVER_PORT:-3000}"
HEADLESS="${HEADLESS:-0}"
RECORD_FLOW="${RECORD_FLOW:-0}"
KEEP_STACK_RUNNING="${KEEP_STACK_RUNNING:-0}"
SKIP_STACK="${SKIP_STACK:-0}"
ALLOW_PREVIEW_AVD="${ALLOW_PREVIEW_AVD:-0}"
AVD_NAME="${AVD_NAME:-}"
DEVICE_SERIAL="${DEVICE_SERIAL:-}"
TEST_CLASS="${TEST_CLASS:-com.cryptohunt.app.OnboardingLobbyFlowTest}"
DEPLOY_SIMULATION="${DEPLOY_SIMULATION:-0}"
SIM_TITLE="${SIM_TITLE:-Simulation Game E2E}"
SIM_PLAYER_PRIVATE_KEY="${SIM_PLAYER_PRIVATE_KEY:-0xb76b6c4243dd5b149b218bcdeb4cc47a511764383af114dddb814b7aca41f52e}"
SEED_SAMPLE_GAMES="${SEED_SAMPLE_GAMES:-}"
if [ -z "$SEED_SAMPLE_GAMES" ]; then
  if [ "$DEPLOY_SIMULATION" = "1" ]; then
    SEED_SAMPLE_GAMES="0"
  else
    SEED_SAMPLE_GAMES="1"
  fi
fi

RPC_URL_HOST="http://127.0.0.1:${ANVIL_PORT}"
RPC_WS_URL_HOST="ws://127.0.0.1:${ANVIL_PORT}"
RPC_URL_ANDROID="http://10.0.2.2:${ANVIL_PORT}"
RPC_WS_URL_ANDROID="ws://10.0.2.2:${ANVIL_PORT}"
SERVER_URL="http://127.0.0.1:${SERVER_PORT}"

DEPLOYER_KEY="${DEPLOYER_KEY:-0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80}"
DB_PATH="${DB_PATH:-$SERVER_DIR/data/app-e2e.db}"
SIM_PLAYER_COUNT="${SIM_PLAYER_COUNT:-3}"
SIM_SPEED_MULTIPLIER="${SIM_SPEED_MULTIPLIER:-35}"
SIM_REGISTRATION_DELAY_SECONDS="${SIM_REGISTRATION_DELAY_SECONDS:-12}"
SIM_GAME_START_DELAY_SECONDS="${SIM_GAME_START_DELAY_SECONDS:-6}"
SIM_MAX_DURATION_SECONDS="${SIM_MAX_DURATION_SECONDS:-180}"
SIM_INITIAL_RADIUS_METERS="${SIM_INITIAL_RADIUS_METERS:-600}"
SIM_GAME_ID=""
if [ "$DEPLOY_SIMULATION" = "1" ]; then
  CHECKIN_DURATION_SECONDS="${CHECKIN_DURATION_SECONDS:-15}"
  PREGAME_DURATION_SECONDS="${PREGAME_DURATION_SECONDS:-8}"
else
  CHECKIN_DURATION_SECONDS="${CHECKIN_DURATION_SECONDS:-300}"
  PREGAME_DURATION_SECONDS="${PREGAME_DURATION_SECONDS:-180}"
fi

ANVIL_LOG="$RUNTIME_DIR/anvil.log"
SERVER_LOG="$RUNTIME_DIR/server.log"
EMULATOR_LOG="$RUNTIME_DIR/emulator.log"
TEST_LOG="$RUNTIME_DIR/android-test.log"
VIDEO_LOCAL="$RUNTIME_DIR/app-flow.mp4"
VIDEO_DEVICE="/sdcard/chain-assassin-e2e.mp4"
RUNTIME_ENV="$RUNTIME_DIR/runtime.env"

ANVIL_PID=""
SERVER_PID=""
EMULATOR_PID=""
SCREENRECORD_PID=""
STARTED_EMULATOR=0
CONTRACT_ADDRESS=""

mkdir -p "$RUNTIME_DIR" "$SERVER_DIR/data"

resolve_cmd() {
  local command_name="$1"
  shift || true
  local fallback_paths=("$@")

  if command -v "$command_name" >/dev/null 2>&1; then
    command -v "$command_name"
    return 0
  fi

  local path_candidate
  for path_candidate in "${fallback_paths[@]}"; do
    if [ -x "$path_candidate" ]; then
      echo "$path_candidate"
      return 0
    fi
  done

  return 1
}

require_cmd() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

wait_for_rpc() {
  local url="$1"
  local tries=50
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
  local tries=80
  for _ in $(seq 1 "$tries"); do
    if curl -s "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

wait_for_emulator_boot() {
  local adb_bin="$1"
  local serial="$2"
  local tries=180
  for _ in $(seq 1 "$tries"); do
    local boot_done
    boot_done="$("$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [ "$boot_done" = "1" ]; then
      "$adb_bin" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
      return 0
    fi
    sleep 2
  done
  return 1
}

pick_default_avd() {
  local emulator_bin="$1"
  local avd_list
  avd_list="$("$emulator_bin" -list-avds)"
  if [ -z "$avd_list" ]; then
    echo ""
    return 0
  fi

  local preferred
  for preferred in 34 35 33; do
    local avd
    while IFS= read -r avd; do
      [ -z "$avd" ] && continue
      if [[ "$avd" == *"API_${preferred}"* ]]; then
        echo "$avd"
        return 0
      fi
    done <<< "$avd_list"
  done

  local avd
  while IFS= read -r avd; do
    [ -z "$avd" ] && continue
    if [[ "$avd" != *"."* ]] && [[ "$avd" != *"preview"* ]] && [[ "$avd" != *"Preview"* ]]; then
      echo "$avd"
      return 0
    fi
  done <<< "$avd_list"

  printf '%s\n' "$avd_list" | head -n 1
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

escape_single_quotes() {
  printf '%s' "$1" | sed "s/'/'\"'\"'/g"
}

deploy_simulation() {
  local auth_blob
  auth_blob="$(
    cd "$SERVER_DIR" && \
      OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY" node --input-type=module <<'NODE'
import { Wallet } from "ethers";

const wallet = new Wallet(process.env.OPERATOR_PRIVATE_KEY);
const message = `chain-assassin:${Math.floor(Date.now() / 1000)}`;
const signature = await wallet.signMessage(message);
console.log(wallet.address);
console.log(message);
console.log(signature);
NODE
  )"

  local operator_address auth_message auth_signature
  operator_address="$(printf '%s\n' "$auth_blob" | sed -n '1p')"
  auth_message="$(printf '%s\n' "$auth_blob" | sed -n '2p')"
  auth_signature="$(printf '%s\n' "$auth_blob" | sed -n '3p')"

  if [ -z "$operator_address" ] || [ -z "$auth_message" ] || [ -z "$auth_signature" ]; then
    echo "Failed to build simulation auth headers" >&2
    exit 1
  fi

  local payload
  payload="$(cat <<EOF
{"playerCount":${SIM_PLAYER_COUNT},"speedMultiplier":${SIM_SPEED_MULTIPLIER},"title":"${SIM_TITLE}","entryFeeWei":"0","baseRewardWei":"0","registrationDelaySeconds":${SIM_REGISTRATION_DELAY_SECONDS},"gameStartDelaySeconds":${SIM_GAME_START_DELAY_SECONDS},"maxDurationSeconds":${SIM_MAX_DURATION_SECONDS},"initialRadiusMeters":${SIM_INITIAL_RADIUS_METERS}}
EOF
)"

  local response
  response="$(
    curl -sS -X POST "${SERVER_URL}/api/simulation/deploy" \
      -H "X-Address: ${operator_address}" \
      -H "X-Message: ${auth_message}" \
      -H "X-Signature: ${auth_signature}" \
      -H "Content-Type: application/json" \
      -d "$payload"
  )"

  if printf '%s' "$response" | grep -q '"error"'; then
    echo "Simulation deploy failed: $response" >&2
    exit 1
  fi

  SIM_GAME_ID="$(printf '%s' "$response" | sed -n 's/.*"gameId":[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  if [ -z "$SIM_GAME_ID" ]; then
    SIM_GAME_ID="0"
  fi

  if [ "$SIM_GAME_ID" -eq 0 ]; then
    local status phase tries
    for tries in $(seq 1 120); do
      status="$(curl -sS "${SERVER_URL}/api/simulation/status" || true)"
      SIM_GAME_ID="$(printf '%s' "$status" | sed -n 's/.*"gameId":[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
      phase="$(printf '%s' "$status" | sed -n 's/.*"phase":"\([^"]*\)".*/\1/p')"
      if [ "$phase" = "aborted" ]; then
        echo "Simulation entered aborted phase before game creation. Status: $status" >&2
        exit 1
      fi
      if [ -n "$SIM_GAME_ID" ] && [ "$SIM_GAME_ID" -gt 0 ]; then
        break
      fi
      sleep 1
    done
  fi

  if [ -z "$SIM_GAME_ID" ] || [ "$SIM_GAME_ID" -eq 0 ]; then
    echo "Could not resolve simulation game ID. Initial response: $response" >&2
    exit 1
  fi

  echo "Simulation deployed: gameId=$SIM_GAME_ID title=\"$SIM_TITLE\""
}

cleanup() {
  set +e

  if [ -n "$SCREENRECORD_PID" ] && kill -0 "$SCREENRECORD_PID" >/dev/null 2>&1; then
    if [ -n "${ADB_BIN:-}" ] && [ -n "$DEVICE_SERIAL" ]; then
      "$ADB_BIN" -s "$DEVICE_SERIAL" shell pkill -INT screenrecord >/dev/null 2>&1 || true
      sleep 1
    fi
    kill "$SCREENRECORD_PID" >/dev/null 2>&1 || true
    wait "$SCREENRECORD_PID" >/dev/null 2>&1 || true
  fi

  if [ "$RECORD_FLOW" = "1" ] && [ -n "${ADB_BIN:-}" ] && [ -n "$DEVICE_SERIAL" ]; then
    "$ADB_BIN" -s "$DEVICE_SERIAL" pull "$VIDEO_DEVICE" "$VIDEO_LOCAL" >/dev/null 2>&1 || true
  fi

  if [ "$KEEP_STACK_RUNNING" != "1" ]; then
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
      kill "$SERVER_PID" >/dev/null 2>&1 || true
    fi
    if [ -n "$ANVIL_PID" ] && kill -0 "$ANVIL_PID" >/dev/null 2>&1; then
      kill "$ANVIL_PID" >/dev/null 2>&1 || true
    fi
  fi

  if [ "$KEEP_STACK_RUNNING" != "1" ] && [ "$STARTED_EMULATOR" = "1" ] && [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    kill "$EMULATOR_PID" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT INT TERM

require_cmd curl
require_cmd lsof
require_cmd node
require_cmd npm

if [ "$SKIP_STACK" != "1" ]; then
  require_cmd anvil
  require_cmd forge
fi

ADB_BIN="$(resolve_cmd adb "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb" || true)"
EMULATOR_BIN="$(resolve_cmd emulator "${ANDROID_HOME:-}/emulator/emulator" "${ANDROID_SDK_ROOT:-}/emulator/emulator" "$HOME/Library/Android/sdk/emulator/emulator" || true)"
if [ -z "$ADB_BIN" ] || [ ! -x "$ADB_BIN" ]; then
  echo "Could not resolve adb. Install Android platform-tools." >&2
  exit 1
fi
if [ -z "$EMULATOR_BIN" ] || [ ! -x "$EMULATOR_BIN" ]; then
  echo "Could not resolve emulator binary. Install Android emulator tools." >&2
  exit 1
fi

if [ "$SKIP_STACK" != "1" ]; then
  ensure_deps "$SERVER_DIR"
  kill_on_port "$ANVIL_PORT"
  kill_on_port "$SERVER_PORT"
  rm -f "$ANVIL_LOG" "$SERVER_LOG" "$TEST_LOG" "$VIDEO_LOCAL"
  rm -f "$DB_PATH" "$DB_PATH-wal" "$DB_PATH-shm"

  echo "Starting Anvil on :$ANVIL_PORT ..."
  anvil --silent --port "$ANVIL_PORT" >"$ANVIL_LOG" 2>&1 &
  ANVIL_PID="$!"
  if ! wait_for_rpc "$RPC_URL_HOST"; then
    echo "Anvil failed to start. See $ANVIL_LOG" >&2
    exit 1
  fi

  echo "Deploying contract ..."
  DEPLOY_OUTPUT="$(
    cd "$CONTRACTS_DIR" && \
      forge script script/Deploy.s.sol \
        --rpc-url "$RPC_URL_HOST" \
        --private-key "$DEPLOYER_KEY" \
        --broadcast 2>&1
  )"
  CONTRACT_ADDRESS="$(echo "$DEPLOY_OUTPUT" | grep "ChainAssassin deployed at:" | tail -n 1 | awk '{print $NF}')"
  if [ -z "${CONTRACT_ADDRESS:-}" ]; then
    echo "Failed to parse contract address from deployment output" >&2
    echo "$DEPLOY_OUTPUT" >&2
    exit 1
  fi

  if [ "$SEED_SAMPLE_GAMES" = "1" ]; then
    echo "Seeding sample games ..."
    (
      cd "$SERVER_DIR"
      RPC_URL="$RPC_URL_HOST" \
      CONTRACT_ADDRESS="$CONTRACT_ADDRESS" \
      OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY" \
      node scripts/seed-sample-games.mjs
    ) >/dev/null
  else
    echo "Skipping sample game seeding"
  fi

  echo "Starting server on :$SERVER_PORT ..."
  (
    cd "$SERVER_DIR"
    DB_PATH="$DB_PATH" \
    RPC_URL="$RPC_URL_HOST" \
    RPC_WS_URL="$RPC_WS_URL_HOST" \
    CONTRACT_ADDRESS="$CONTRACT_ADDRESS" \
    OPERATOR_PRIVATE_KEY="$DEPLOYER_KEY" \
    CHAIN_ID=31337 \
    PORT="$SERVER_PORT" \
    HOST=0.0.0.0 \
    ENABLE_SIMULATION_API=true \
    CHECKIN_DURATION_SECONDS="$CHECKIN_DURATION_SECONDS" \
    PREGAME_DURATION_SECONDS="$PREGAME_DURATION_SECONDS" \
    LOG_LEVEL=info \
    npx tsx src/index.ts
  ) >"$SERVER_LOG" 2>&1 &
  SERVER_PID="$!"
  if ! wait_for_http "$SERVER_URL/health"; then
    echo "Server failed to start. See $SERVER_LOG" >&2
    exit 1
  fi

else
  CONTRACT_ADDRESS="${CHAIN_CONTRACT_ADDRESS:-}"
  if [ -z "$CONTRACT_ADDRESS" ]; then
    echo "SKIP_STACK=1 requires CHAIN_CONTRACT_ADDRESS to be set." >&2
    exit 1
  fi
fi

if [ -z "$DEVICE_SERIAL" ]; then
  DEVICE_SERIAL="$("$ADB_BIN" devices | awk '/^emulator-/{print $1; exit}')"
fi

if [ -z "$DEVICE_SERIAL" ]; then
  if [ -z "$AVD_NAME" ]; then
    AVD_NAME="$(pick_default_avd "$EMULATOR_BIN")"
  fi
  if [ -z "$AVD_NAME" ]; then
    cat >&2 <<EOF
No Android AVD found.
Create one in Android Studio Device Manager or run:
  ./scripts/create-avd-api35.sh
EOF
    exit 1
  fi

  if [[ "$AVD_NAME" == *"."* ]] || [[ "$AVD_NAME" == *"preview"* ]] || [[ "$AVD_NAME" == *"Preview"* ]]; then
    if [ "$ALLOW_PREVIEW_AVD" != "1" ]; then
      cat >&2 <<EOF
Selected AVD '$AVD_NAME' is a preview image.
Preview system images are prone to 'System UI isn't responding' ANRs in unattended UI tests.
Use a stable AVD (API 33/34/35), e.g.:
  ./scripts/create-avd-api35.sh
or set ALLOW_PREVIEW_AVD=1 to override.
EOF
      exit 1
    fi
    echo "Warning: using preview AVD ($AVD_NAME). Compose/Espresso tests may be unstable." >&2
  fi

  echo "Starting emulator: $AVD_NAME"
  if [ "$HEADLESS" = "1" ]; then
    "$EMULATOR_BIN" -avd "$AVD_NAME" -no-window -no-audio -netdelay none -netspeed full >"$EMULATOR_LOG" 2>&1 &
  else
    "$EMULATOR_BIN" -avd "$AVD_NAME" -netdelay none -netspeed full >"$EMULATOR_LOG" 2>&1 &
  fi
  EMULATOR_PID="$!"
  STARTED_EMULATOR=1

  echo "Waiting for emulator device ..."
  for _ in $(seq 1 90); do
    DEVICE_SERIAL="$("$ADB_BIN" devices | awk '/^emulator-/{print $1; exit}')"
    if [ -n "$DEVICE_SERIAL" ]; then
      break
    fi
    sleep 1
  done
fi

if [ -z "$DEVICE_SERIAL" ]; then
  echo "No emulator device detected." >&2
  exit 1
fi

echo "Using device: $DEVICE_SERIAL"
if ! wait_for_emulator_boot "$ADB_BIN" "$DEVICE_SERIAL"; then
  echo "Emulator failed to boot. See $EMULATOR_LOG" >&2
  exit 1
fi

echo "Building and installing app + androidTest ..."
CHAIN_CONTRACT_ADDRESS="$CONTRACT_ADDRESS" \
CHAIN_RPC_URL="$RPC_URL_ANDROID" \
CHAIN_RPC_WS_URL="$RPC_WS_URL_ANDROID" \
CHAIN_ID=31337 \
CHAIN_EXPLORER_URL="$RPC_URL_ANDROID" \
CHAIN_NAME="Local Anvil" \
ANDROID_SERIAL="$DEVICE_SERIAL" \
"$ROOT_DIR/scripts/android-gradle.sh" :app:installDebug :app:installDebugAndroidTest

echo "Resetting app state and granting runtime permissions ..."
"$ADB_BIN" -s "$DEVICE_SERIAL" shell pm clear com.cryptohunt.app >/dev/null

# Suppress Android crash/ANR popups that can block unattended UI tests on some emulator images.
"$ADB_BIN" -s "$DEVICE_SERIAL" shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE_SERIAL" shell settings put global show_first_crash_dialog 0 >/dev/null 2>&1 || true
"$ADB_BIN" -s "$DEVICE_SERIAL" shell settings put global anr_show_background 0 >/dev/null 2>&1 || true

grant_permission() {
  local permission="$1"
  "$ADB_BIN" -s "$DEVICE_SERIAL" shell pm grant com.cryptohunt.app "$permission" >/dev/null 2>&1 || true
}

grant_permission android.permission.CAMERA
grant_permission android.permission.ACCESS_FINE_LOCATION
grant_permission android.permission.ACCESS_COARSE_LOCATION
grant_permission android.permission.BLUETOOTH_SCAN
grant_permission android.permission.BLUETOOTH_ADVERTISE
grant_permission android.permission.BLUETOOTH_CONNECT
grant_permission android.permission.POST_NOTIFICATIONS

if [ "$DEPLOY_SIMULATION" = "1" ]; then
  echo "Deploying simulation game via API ..."
  deploy_simulation
fi

if [ "$RECORD_FLOW" = "1" ]; then
  echo "Recording emulator screen ..."
  "$ADB_BIN" -s "$DEVICE_SERIAL" shell rm -f "$VIDEO_DEVICE" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$DEVICE_SERIAL" shell screenrecord --bit-rate 2000000 --size 720x1280 "$VIDEO_DEVICE" >/dev/null 2>&1 &
  SCREENRECORD_PID="$!"
fi

cat >"$RUNTIME_ENV" <<EOF
ANVIL_PORT=${ANVIL_PORT}
SERVER_PORT=${SERVER_PORT}
RPC_URL_HOST=${RPC_URL_HOST}
RPC_URL_ANDROID=${RPC_URL_ANDROID}
CONTRACT_ADDRESS=${CONTRACT_ADDRESS}
DEVICE_SERIAL=${DEVICE_SERIAL}
DEPLOY_SIMULATION=${DEPLOY_SIMULATION}
SIM_GAME_ID=${SIM_GAME_ID}
SIM_TITLE=${SIM_TITLE}
SEED_SAMPLE_GAMES=${SEED_SAMPLE_GAMES}
SIM_REGISTRATION_DELAY_SECONDS=${SIM_REGISTRATION_DELAY_SECONDS}
SIM_GAME_START_DELAY_SECONDS=${SIM_GAME_START_DELAY_SECONDS}
SIM_MAX_DURATION_SECONDS=${SIM_MAX_DURATION_SECONDS}
EOF

echo "Running instrumentation test: $TEST_CLASS"
if [ -n "$DEVICE_SERIAL" ]; then
  "$ADB_BIN" -s "$DEVICE_SERIAL" logcat -c >/dev/null 2>&1 || true
fi
test_class_escaped="$(escape_single_quotes "$TEST_CLASS")"
instrument_cmd="am instrument -w -r --user 0 -e class '$test_class_escaped'"
if [ "$DEPLOY_SIMULATION" = "1" ]; then
  sim_title_escaped="$(escape_single_quotes "$SIM_TITLE")"
  sim_key_escaped="$(escape_single_quotes "$SIM_PLAYER_PRIVATE_KEY")"
  instrument_cmd="$instrument_cmd -e sim_title '$sim_title_escaped' -e import_private_key '$sim_key_escaped'"
fi
instrument_cmd="$instrument_cmd com.cryptohunt.app.test/androidx.test.runner.AndroidJUnitRunner"
set +e
"$ADB_BIN" -s "$DEVICE_SERIAL" shell "$instrument_cmd" | tee "$TEST_LOG"
TEST_EXIT="${PIPESTATUS[0]}"
set -e

if grep -q "FAILURES!!!" "$TEST_LOG" || ! grep -q "OK (" "$TEST_LOG"; then
  TEST_EXIT=1
fi

if [ "$TEST_EXIT" -ne 0 ]; then
  if [ -n "$DEVICE_SERIAL" ]; then
    "$ADB_BIN" -s "$DEVICE_SERIAL" logcat -d -t 1200 > "$RUNTIME_DIR/logcat-last.txt" 2>/dev/null || true
    echo "Emulator logcat saved: $RUNTIME_DIR/logcat-last.txt" >&2
  fi
  echo "Android E2E test failed. Logs: $TEST_LOG" >&2
  exit "$TEST_EXIT"
fi

echo ""
echo "Android E2E completed successfully."
echo "  Server:    $SERVER_URL"
echo "  Contract:  $CONTRACT_ADDRESS"
echo "  Test log:  $TEST_LOG"
if [ "$DEPLOY_SIMULATION" = "1" ]; then
  echo "  Sim game:  ${SIM_GAME_ID} (${SIM_TITLE})"
fi
if [ "$RECORD_FLOW" = "1" ]; then
  echo "  Recording: $VIDEO_LOCAL"
fi
