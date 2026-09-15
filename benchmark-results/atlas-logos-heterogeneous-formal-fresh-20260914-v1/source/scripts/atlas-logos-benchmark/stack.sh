#!/usr/bin/env bash
# Own only the benchmark's Compose project; never remove its evidence or volumes.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/build/atlas-logos-benchmark"
SOURCE="$STATE/edutelligence"
PIN=0d4a6683171cd956e1cf46a19d49e99181a52ac4
ENV_FILE="$STATE/stack.env"
mkdir -p "$STATE"
if [[ ! -f "$ENV_FILE" ]]; then
  (umask 077; python3 - <<'PY' > "$ENV_FILE"
import secrets
print('LOGOS_INTERNAL_SECRET=' + secrets.token_hex(32))
print('LOGOS_API_KEY=' + secrets.token_hex(32))
PY
  )
fi
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT/docker/atlas-logos-benchmark/compose.yaml")
case "${1:-status}" in
  setup)
    if [[ ! -d "$SOURCE/.git" ]]; then
      git clone --filter=blob:none --no-checkout https://github.com/ls1intum/edutelligence.git "$SOURCE"
      git -C "$SOURCE" sparse-checkout set logos shared
      git -C "$SOURCE" checkout --detach "$PIN"
    fi
    [[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$PIN" ]] || { echo 'Logos source revision differs from pin.' >&2; exit 1; }
    "${COMPOSE[@]}" config --quiet
    ;;
  up)
    "$0" setup
    "${COMPOSE[@]}" up -d --build logos-db keycloak logos-webservice artemis-db
    # The webservice owns schema migration; do not start routing against an empty database.
    python3 "$ROOT/docker/atlas-logos-benchmark/setup.py" wait-schema
    "${COMPOSE[@]}" up -d --build logos-orchestrator
    ;;
  provision|models|export)
    python3 "$ROOT/docker/atlas-logos-benchmark/setup.py" "$@"
    ;;
  server)
    : "${ATLAS_BENCHMARK_EVIDENCE_DIR:?Set the same absolute evidence directory for server and runner}"
    export ATLAS_BENCHMARK_PRICING_FILE="${ATLAS_BENCHMARK_PRICING_FILE:-$ROOT/docker/atlas-logos-benchmark/pricing.json}"
    if [[ -n "${JAVA_HOME:-}" ]]; then
      export PATH="$JAVA_HOME/bin:$PATH"
    fi
    java -version 2>&1 | head -n 1 | grep -q '"25' || { echo 'Use Java 25 (set JAVA_HOME if needed).' >&2; exit 1; }
    export LOGOS_BASE_URL="${LOGOS_BASE_URL:-http://127.0.0.1:18090/v1}"
    if [[ -z "${LOGOS_API_KEY:-}" && "$LOGOS_BASE_URL" == 'http://127.0.0.1:18090/v1' ]]; then
      LOGOS_API_KEY="$(python3 - "$ENV_FILE" <<'PY'
import sys
from pathlib import Path
for line in Path(sys.argv[1]).read_text().splitlines():
    if line.startswith('LOGOS_API_KEY='):
        print(line.split('=', 1)[1])
        break
else:
    raise SystemExit('Local Logos key missing')
PY
      )"
    fi
    : "${LOGOS_API_KEY:?Set the API key issued by your Logos instance}"
    export LOGOS_API_KEY
    unset OPENAI_API_KEY OPENAI_SECRET_FILE
    cd "$ROOT"
    exec ./gradlew bootRun -x webapp --args='--spring.profiles.active=dev,artemis,localci,localvc,core,scheduling,atlas-logos-benchmark'
    ;;
  status) "${COMPOSE[@]}" ps ;;
  stop|down) "${COMPOSE[@]}" stop ;;
  logs) "${COMPOSE[@]}" logs --tail 80 "${@:2}" ;;
  *) echo 'Usage: stack.sh setup|up|provision|models|export|server|status|stop|logs' >&2; exit 2 ;;
esac
