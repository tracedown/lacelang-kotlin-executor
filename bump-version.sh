#!/usr/bin/env bash
# bump-version.sh — bump package and/or spec version across all files in this repo.
#
# Usage:
#   bash bump-version.sh                          # patch bump package version
#   bash bump-version.sh 0.2.0                    # explicit package version
#   bash bump-version.sh --spec 0.10.0            # bump spec/AST version only
#   bash bump-version.sh 0.2.0 --spec 0.10.0     # bump both

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Parse args ────────────────────────────────────────────────────────

new_pkg=""
new_spec=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --spec) new_spec="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: bash bump-version.sh [pkg-version] [--spec spec-version]"
            exit 0 ;;
        *) new_pkg="$1"; shift ;;
    esac
done

# ── Read current versions ─────────────────────────────────────────────

cur_pkg=$(grep -oP '^version = "\K[^"]+' "$SCRIPT_DIR/build.gradle.kts")
cur_spec=$(grep -oP 'conforms_to = ">=\K[^"]+' "$SCRIPT_DIR/lace-executor.toml")

# ── Auto-patch if no explicit package version ─────────────────────────

if [[ -z "$new_pkg" && -z "$new_spec" ]]; then
    IFS='.' read -r major minor patch <<< "$cur_pkg"
    new_pkg="$major.$minor.$((patch + 1))"
fi

# ── Apply package version ─────────────────────────────────────────────

if [[ -n "$new_pkg" ]]; then
    echo "Package: $cur_pkg -> $new_pkg"
    sed -i "s/^version = \"$cur_pkg\"/version = \"$new_pkg\"/" \
        "$SCRIPT_DIR/build.gradle.kts"
    sed -i "s/lacelang-kt-validator:$cur_pkg/lacelang-kt-validator:$new_pkg/" \
        "$SCRIPT_DIR/build.gradle.kts"
    sed -i "s/version     = \"$cur_pkg\"/version     = \"$new_pkg\"/" \
        "$SCRIPT_DIR/lace-executor.toml"
    sed -i "s/lacelang-kt-executor-$cur_pkg/lacelang-kt-executor-$new_pkg/g" \
        "$SCRIPT_DIR/lace-executor.toml"
    sed -i "s/VERSION = \"$cur_pkg\"/VERSION = \"$new_pkg\"/" \
        "$SCRIPT_DIR/src/main/kotlin/dev/lacelang/executor/Executor.kt"
    echo "  build.gradle.kts, Executor.kt, lace-executor.toml"
fi

# ── Apply spec version ────────────────────────────────────────────────

if [[ -n "$new_spec" ]]; then
    echo "Spec: $cur_spec -> $new_spec"
    sed -i "s/>=$cur_spec\"/>=$new_spec\"/" \
        "$SCRIPT_DIR/lace-executor.toml"
    echo "  lace-executor.toml"
fi
