#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/app-android"
WRAPPER_PROPS="$APP_DIR/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "Missing $WRAPPER_PROPS" >&2
  exit 1
fi

if [ ! -d "$APP_DIR" ]; then
  echo "Missing Android project directory: $APP_DIR" >&2
  exit 1
fi

if [ "$#" -eq 0 ]; then
  TASKS=(":app:compileDebugKotlin")
else
  TASKS=("$@")
fi

resolve_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    echo "$JAVA_HOME"
    return 0
  fi

  local candidates=(
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"
  )

  local j
  for j in "${candidates[@]}"; do
    if [ -x "$j/bin/java" ]; then
      echo "$j"
      return 0
    fi
  done

  return 1
}

resolve_gradle_bin() {
  local dist_url
  dist_url="$(grep '^distributionUrl=' "$WRAPPER_PROPS" | cut -d'=' -f2- | tr -d '\r')"
  if [ -z "${dist_url:-}" ]; then
    return 1
  fi

  local dist_name
  dist_name="$(basename "${dist_url}" .zip)"
  local base="$HOME/.gradle/wrapper/dists/$dist_name"

  if [ ! -d "$base" ]; then
    return 1
  fi

  local gradle_bin
  gradle_bin="$(find "$base" -type f -path '*/bin/gradle' | head -n 1)"
  if [ -z "${gradle_bin:-}" ] || [ ! -x "$gradle_bin" ]; then
    return 1
  fi

  echo "$gradle_bin"
  return 0
}

JAVA_HOME_RESOLVED="$(resolve_java_home || true)"
if [ -z "${JAVA_HOME_RESOLVED:-}" ]; then
  echo "Could not resolve JAVA_HOME." >&2
  echo "Install Android Studio or set JAVA_HOME to a valid JDK." >&2
  exit 1
fi

GRADLE_BIN="$(resolve_gradle_bin || true)"
if [ -z "${GRADLE_BIN:-}" ]; then
  echo "Could not find Gradle distribution for wrapper config." >&2
  echo "Expected distribution from: $WRAPPER_PROPS" >&2
  echo "Open the project once in Android Studio to download it." >&2
  exit 1
fi

echo "JAVA_HOME: $JAVA_HOME_RESOLVED"
echo "Gradle:    $GRADLE_BIN"
echo "Project:   $APP_DIR"
echo "Tasks:     ${TASKS[*]}"

cd "$APP_DIR"
JAVA_HOME="$JAVA_HOME_RESOLVED" "$GRADLE_BIN" --no-daemon "${TASKS[@]}"
