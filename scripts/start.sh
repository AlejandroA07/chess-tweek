#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_JAR="$PROJECT_ROOT/target/chess-tweek-0.1.0-SNAPSHOT.jar"
BUILD_MARKER="$PROJECT_ROOT/target/.chess-tweek-verified-ref"
CHESS_TWEEK_JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
JAVA_BINARY="$CHESS_TWEEK_JAVA_HOME/bin/java"
CURRENT_REVISION=$(git -C "$PROJECT_ROOT" rev-parse HEAD)

if [ ! -x "$JAVA_BINARY" ]; then
  echo "Java 25 was not found at $CHESS_TWEEK_JAVA_HOME" >&2
  echo "Install it with: brew install openjdk@25" >&2
  exit 1
fi

needs_build=false
if [ ! -f "$APP_JAR" ] || [ ! -f "$BUILD_MARKER" ]; then
  needs_build=true
elif [ "$(cat "$BUILD_MARKER")" != "$CURRENT_REVISION" ]; then
  needs_build=true
elif [ -n "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=all -- pom.xml package.json pnpm-lock.yaml frontend src)" ]; then
  needs_build=true
fi

if [ "$needs_build" = true ]; then
  echo "Building and verifying Chess Tweek..."
  node "$PROJECT_ROOT/scripts/verify.mjs"
  printf '%s\n' "$CURRENT_REVISION" > "$BUILD_MARKER"
fi

echo "Starting Chess Tweek at http://127.0.0.1:8080"
echo "Press Ctrl+C to stop."
cd "$PROJECT_ROOT"
exec "$JAVA_BINARY" -jar "$APP_JAR"
