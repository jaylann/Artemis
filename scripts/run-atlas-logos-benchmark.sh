#!/usr/bin/env bash
# One entry point for the Atlas benchmark and optional local stack.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT/scripts/atlas-logos-benchmark/run.py"
STACK="$ROOT/scripts/atlas-logos-benchmark/stack.sh"
COMMAND="${1:-prepare}"
shift || true

case "$COMMAND" in
    prepare|smoke|run|report|stop) exec python3 "$RUNNER" "$COMMAND" "$@" ;;
    stack) exec "$STACK" "$@" ;;
    server) exec "$STACK" server "$@" ;;
    *) echo "Usage: $0 prepare|smoke|run|report|stop|server|stack" >&2; exit 2 ;;
esac
