#!/usr/bin/env bash
# Run the copied demo with distinct runtime resources and an external request meter.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export DEMO_HTTP_PORT=8083 DEMO_DB_PORT=55433 DEMO_SSH_PORT=7924 DEMO_HAZELCAST_PORT=5704
export DEMO_COMPOSE_PROJECT=atlas-cost-benchmark-20260922 DEMO_CLUSTER=atlas-cost-benchmark-20260922
export DEMO_OPENAI_BASE_URL=http://127.0.0.1:18091/v1
export DEMO_ATLAS_MODEL=gpt-5.6-luna
# Native NO_OP completions have no STOMP message; retain their existing terminal log.
export SPRING_APPLICATION_JSON='{"logging":{"level":{"de.tum.cit.aet.artemis.atlas.service.ContentChangeScheduler":"DEBUG"}}}'
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
supporting_scripts/demo/run-demo.sh "${1:-start}"
if [[ "${1:-start}" == build ]]; then
    python3 supporting_scripts/benchmark/campaign.py stamp-build
fi
