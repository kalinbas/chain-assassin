#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${API_LEVEL:-35}"
AVD_NAME="${AVD_NAME:-ChainAssassin_API_${API_LEVEL}}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-${API_LEVEL};google_apis;x86_64}"
DEVICE_PROFILE="${DEVICE_PROFILE:-pixel_7}"
FORCE_RECREATE="${FORCE_RECREATE:-0}"
ACCEPT_LICENSES="${ACCEPT_LICENSES:-1}"

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

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"

SDKMANAGER_BIN="$(
  resolve_cmd sdkmanager \
    "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" \
    "$ANDROID_SDK/cmdline-tools/bin/sdkmanager" \
    "$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager" \
    "$HOME/Library/Android/sdk/cmdline-tools/bin/sdkmanager"
)" || true

AVDMANAGER_BIN="$(
  resolve_cmd avdmanager \
    "$ANDROID_SDK/cmdline-tools/latest/bin/avdmanager" \
    "$ANDROID_SDK/cmdline-tools/bin/avdmanager" \
    "$HOME/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager" \
    "$HOME/Library/Android/sdk/cmdline-tools/bin/avdmanager"
)" || true

EMULATOR_BIN="$(
  resolve_cmd emulator \
    "$ANDROID_SDK/emulator/emulator" \
    "$HOME/Library/Android/sdk/emulator/emulator"
)" || true

if [ -z "${SDKMANAGER_BIN:-}" ] || [ ! -x "$SDKMANAGER_BIN" ]; then
  echo "Could not resolve sdkmanager. Install Android SDK Command-line Tools first." >&2
  exit 1
fi

if [ -z "${AVDMANAGER_BIN:-}" ] || [ ! -x "$AVDMANAGER_BIN" ]; then
  echo "Could not resolve avdmanager. Install Android SDK Command-line Tools first." >&2
  exit 1
fi

if [ -z "${EMULATOR_BIN:-}" ] || [ ! -x "$EMULATOR_BIN" ]; then
  echo "Could not resolve emulator binary. Install Android Emulator tools first." >&2
  exit 1
fi

echo "Android SDK: $ANDROID_SDK"
echo "AVD name:    $AVD_NAME"
echo "API level:   $API_LEVEL"
echo "Image:       $SYSTEM_IMAGE"
echo "Device:      $DEVICE_PROFILE"

if [ "$ACCEPT_LICENSES" = "1" ]; then
  echo "Accepting Android SDK licenses ..."
  yes | "$SDKMANAGER_BIN" --licenses >/dev/null || true
fi

echo "Installing required SDK packages ..."
"$SDKMANAGER_BIN" --install \
  "platform-tools" \
  "emulator" \
  "platforms;android-${API_LEVEL}" \
  "$SYSTEM_IMAGE"

avd_exists() {
  "$EMULATOR_BIN" -list-avds | awk '{print $0}' | grep -Fxq "$AVD_NAME"
}

if avd_exists; then
  if [ "$FORCE_RECREATE" = "1" ]; then
    echo "Deleting existing AVD: $AVD_NAME"
    "$AVDMANAGER_BIN" delete avd -n "$AVD_NAME" >/dev/null 2>&1 || true
  else
    echo "AVD already exists: $AVD_NAME"
    echo "Done."
    echo "Run: AVD_NAME=\"$AVD_NAME\" ./scripts/run-app-e2e.sh"
    exit 0
  fi
fi

echo "Creating AVD ..."
if ! printf 'no\n' | "$AVDMANAGER_BIN" create avd \
  -n "$AVD_NAME" \
  -k "$SYSTEM_IMAGE" \
  --device "$DEVICE_PROFILE" \
  --force >/dev/null; then
  echo "Device profile '$DEVICE_PROFILE' not found, retrying with default profile ..."
  printf 'no\n' | "$AVDMANAGER_BIN" create avd \
    -n "$AVD_NAME" \
    -k "$SYSTEM_IMAGE" \
    --force >/dev/null
fi

if ! avd_exists; then
  echo "Failed to create AVD: $AVD_NAME" >&2
  exit 1
fi

echo "AVD created successfully: $AVD_NAME"
echo "Run: AVD_NAME=\"$AVD_NAME\" ./scripts/run-app-e2e.sh"
