#!/bin/bash
# Run the lace conformance suite via WSL.
# Prerequisites: Java 17+ available in WSL (apt install openjdk-17-jre-headless)
# Usage: wsl bash scripts/run-conformance.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LACELANG_ROOT="$(cd "$ROOT/../lacelang" && pwd)"

# 1. Build shadow jar (if not already built)
if [ ! -f "$ROOT/build/libs/lacelang-kt-executor-0.1.0-all.jar" ]; then
    echo "Building shadow JAR..."
    cd "$ROOT" && ./gradlew shadowJar
fi

# 2. Download conformance binary if not present
CONFORMANCE_BIN="$ROOT/build/lace-conformance"
if [ ! -f "$CONFORMANCE_BIN" ]; then
    echo "Downloading conformance binary..."
    curl -sL "https://github.com/tracedown/lacelang/releases/download/testkit-v0.9.1/lace-conformance-linux-x86_64" \
        -o "$CONFORMANCE_BIN"
    chmod +x "$CONFORMANCE_BIN"
fi

# 3. Generate TLS test certs if not present
CERTS_DIR="$LACELANG_ROOT/testkit/certs"
if [ ! -f "/tmp/lace-certs/server.crt" ]; then
    echo "Generating TLS test certificates..."
    mkdir -p /tmp/lace-certs
    bash "$CERTS_DIR/generate-certs.sh" /tmp/lace-certs
fi

# 4. Run conformance suite
echo "Running conformance suite..."
cd "$ROOT"
"$CONFORMANCE_BIN" -m lace-executor.toml --certs-dir /tmp/lace-certs "$@"
