#!/usr/bin/env bash
# All mutable runtime files are kept in this worktree's ignored .demo directory.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
STATE="$ROOT/.demo"
mkdir -p "$STATE"
export DEMO_HTTP_PORT="${DEMO_HTTP_PORT:-8082}"
export DEMO_DB_PORT="${DEMO_DB_PORT:-55432}"
export DEMO_SSH_PORT="${DEMO_SSH_PORT:-7923}"
export DEMO_HAZELCAST_PORT="${DEMO_HAZELCAST_PORT:-5703}"
export DEMO_URL="http://localhost:$DEMO_HTTP_PORT"
compose() { docker compose -p "${DEMO_COMPOSE_PROJECT:-artemis-atlas-demo}" -f "$ROOT/supporting_scripts/demo/compose.yml" "$@"; }
case "${1:-start}" in
  new|seed|verify) exec node supporting_scripts/demo/seed-demo.mjs "$@" ;;
  stop)
    if [[ -f "$STATE/server.pid" ]]; then
      pid=$(cat "$STATE/server.pid")
      if ps -p "$pid" -o args= | grep -F -- "-Datlas.demo.root=$ROOT" >/dev/null; then kill "$pid"; fi
      rm -f "$STATE/server.pid"
    fi
    compose stop
    exit ;;
  start|build) ;;
  *) echo 'Usage: run-demo.sh [start|build|new|seed|verify|stop] [--batch NAME] [--refresh-content]'; exit 2 ;;
esac
if [[ -z "${JAVA_HOME:-}" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25'; then
  if [[ -d "$HOME/.sdkman/candidates/java/25.0.2-amzn" ]]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.2-amzn"
  else
    echo 'Set JAVA_HOME to a Java 25 installation.' >&2; exit 1
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
if [[ "${1:-start}" == build ]] || ! [[ -f "$STATE/artemis.war" ]]; then
  pnpm install --frozen-lockfile
  # A cached prebuilt client is suitable only for packaging, not for an explicit rebuild.
  rm -rf build/webapp-dist
  ./gradlew -Pprod -Pwar bootWar -x test --console=plain
  war=$(find build/libs -maxdepth 1 -name '*.war' ! -name '*-plain.war' -print -quit)
  [[ -n "$war" ]] || { echo 'WAR missing after build' >&2; exit 1; }
  cp "$war" "$STATE/artemis.war"
  [[ "${1:-start}" == build ]] && exit 0
fi
if [[ -f "$STATE/server.pid" ]] && kill -0 "$(cat "$STATE/server.pid")" 2>/dev/null; then
  echo "Demo already running: $DEMO_URL"; exit 0
fi
# A previously stopped demo database can be resumed; never claim another listener.
if ! compose ps --status running --services | grep -qx postgres; then
  node --input-type=module -e 'import net from "node:net"; const s=net.createServer(); s.once("error",e=>{console.error(`Database port ${process.env.DEMO_DB_PORT} unavailable: ${e.message}`);process.exit(1)});s.listen(+process.env.DEMO_DB_PORT,"127.0.0.1",()=>s.close());'
fi
node --input-type=module <<'JS'
import net from 'node:net';
for (const key of ['DEMO_HTTP_PORT','DEMO_SSH_PORT','DEMO_HAZELCAST_PORT']) {
  await new Promise((resolve,reject)=>{const s=net.createServer();s.once('error',reject);s.listen(+process.env[key],'127.0.0.1',()=>s.close(resolve));}).catch(e=>{throw Error(`${key}=${process.env[key]} unavailable: ${e.message}`)});
}
JS
compose up -d --wait
export SPRING_PROFILES_ACTIVE=artemis,scheduling,localvc,localci,buildagent,core,dev
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$DEMO_DB_PORT/atlas_demo"
export SPRING_DATASOURCE_USERNAME=atlas_demo SPRING_DATASOURCE_PASSWORD=atlas_demo_local
export SPRING_LIQUIBASE_CONTEXTS=dev
export SPRING_HAZELCAST_PORT="$DEMO_HAZELCAST_PORT" SPRING_HAZELCAST_LOCALINSTANCES=false
export SPRING_HAZELCAST_CLUSTERNAME="${DEMO_CLUSTER:-atlas-demo}"
export SPRING_JPA_PROPERTIES_HIBERNATE_CACHE_HAZELCAST_INSTANCE_NAME="$SPRING_HAZELCAST_CLUSTERNAME"
export EUREKA_CLIENT_ENABLED=false SPRING_CLOUD_DISCOVERY_ENABLED=false
export LOGGING_LEVEL_ROOT=INFO LOGGING_LEVEL_DE_TUM_CIT_AET_ARTEMIS=INFO
export SERVER_PORT="$DEMO_HTTP_PORT" SERVER_ADDRESS=127.0.0.1 SERVER_URL="$DEMO_URL"
export ARTEMIS_USERMANAGEMENT_USEEXTERNAL=false
export ARTEMIS_USERMANAGEMENT_INTERNALADMIN_USERNAME="${DEMO_USER:-artemis_admin}"
export ARTEMIS_USERMANAGEMENT_INTERNALADMIN_PASSWORD="${DEMO_PASSWORD:-artemis_admin}"
export ARTEMIS_VERSIONCONTROL_URL="$DEMO_URL"
export ARTEMIS_VERSIONCONTROL_USER="$ARTEMIS_USERMANAGEMENT_INTERNALADMIN_USERNAME"
export ARTEMIS_VERSIONCONTROL_PASSWORD="$ARTEMIS_USERMANAGEMENT_INTERNALADMIN_PASSWORD"
export ARTEMIS_VERSIONCONTROL_SSHPORT="$DEMO_SSH_PORT"
export ARTEMIS_VERSIONCONTROL_SSHHOSTKEYPATH="$STATE/ssh"
export ARTEMIS_VERSIONCONTROL_LOCALVCSREPOPATH="$STATE/local-vcs"
export ARTEMIS_REPOCLONEPATH="$STATE/repos" ARTEMIS_REPODOWNLOADCLONEPATH="$STATE/repos-download"
export ARTEMIS_FILEUPLOADPATH="$STATE/uploads" ARTEMIS_DATAEXPORTPATH="$STATE/data-exports"
export ARTEMIS_BUILDLOGSPATH="$STATE/build-logs"
export ARTEMIS_CHECKEDOUTREPOSPATH="$STATE/build-agent"
export ARTEMIS_CONTINUOUSINTEGRATION_DOCKERCONNECTIONURI="${DEMO_DOCKER_URI:-unix://$HOME/.docker/run/docker.sock}"
export ARTEMIS_CONTINUOUSINTEGRATION_ARTEMISAUTHENTICATIONTOKENVALUE=atlas-demo-local
export ARTEMIS_CONTINUOUSINTEGRATION_EMPTYCOMMITNECESSARY=true
# Keep this laptop demo small and serialize test-case result persistence.
export ARTEMIS_CONTINUOUSINTEGRATION_SPECIFYCONCURRENTBUILDS=true
export ARTEMIS_CONTINUOUSINTEGRATION_CONCURRENTBUILDSIZE=1
export ARTEMIS_CONTINUOUSINTEGRATION_CONCURRENTRESULTPROCESSINGSIZE=1
export ARTEMIS_GIT_NAME=Artemis ARTEMIS_GIT_EMAIL=artemis@example.com
export ARTEMIS_TELEMETRY_ENABLED=false ARTEMIS_ATLAS_ATLASLLM_ENABLED=true ARTEMIS_ATLAS_ATLASML_ENABLED=false
export ARTEMIS_HYPERION_ENABLED=true ARTEMIS_IRIS_ENABLED=false ARTEMIS_ATHENA_ENABLED=false
export ARTEMIS_GLOBALSEARCH_ENABLED=false
if [[ -z "${OPENAI_API_KEY:-}" && -f "$STATE/openai.env" ]]; then
  # This file contains only OPENAI_API_KEY, is ignored by Git, and must be mode 600.
  set -a
  source "$STATE/openai.env"
  set +a
fi
: "${OPENAI_API_KEY:?Set OPENAI_API_KEY or create .demo/openai.env}"
export SPRING_AI_MODEL_CHAT=openai SPRING_AI_OPENAI_BASEURL="${DEMO_OPENAI_BASE_URL:-https://api.openai.com/v1}"
export SPRING_AI_OPENAI_APIKEY="$OPENAI_API_KEY" SPRING_AI_OPENAI_MICROSOFTFOUNDRY=false
export SPRING_AI_OPENAI_MODEL="${DEMO_CHAT_MODEL:-gpt-5.4-mini}"
export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL="$SPRING_AI_OPENAI_MODEL"
export SPRING_AI_OPENAI_CHAT_OPTIONS_TEMPERATURE=1.0
export ARTEMIS_ATLAS_CHATMODEL="$SPRING_AI_OPENAI_MODEL" ARTEMIS_ATLAS_TEMPERATURE=1.0
export ARTEMIS_ATLAS_ORCHESTRATOR_MODEL="${DEMO_ATLAS_MODEL:-gpt-5.6-luna}"
export ARTEMIS_ATLAS_ORCHESTRATOR_WORKERMODEL="$ARTEMIS_ATLAS_ORCHESTRATOR_MODEL"
export ARTEMIS_ATLAS_ORCHESTRATOR_REASONINGEFFORT=xhigh ARTEMIS_ATLAS_ORCHESTRATOR_WORKERREASONINGEFFORT=high
export ARTEMIS_ATLAS_ORCHESTRATOR_RESPONSESAPIENABLED=true
export ARTEMIS_ATLAS_FLAVORSTRIPMODEL="$ARTEMIS_ATLAS_ORCHESTRATOR_MODEL" ARTEMIS_ATLAS_FLAVORSTRIPREASONINGEFFORT=high
# Detached process survives the launching shell and retains only worktree-local logs.
python3 - "$ROOT" "$STATE" <<'PY'
import os, subprocess, sys
root,state=sys.argv[1:]
with open(state+'/server.log','ab',buffering=0) as log:
 p=subprocess.Popen([os.environ['JAVA_HOME']+'/bin/java','-Xmx4g','-Datlas.demo.root='+root,'--add-opens=java.base/java.lang=ALL-UNNAMED','-jar',state+'/artemis.war'],cwd=root,stdout=log,stderr=log,start_new_session=True)
with open(state+'/server.pid','w') as f:f.write(str(p.pid))
PY
for ((i=0;i<180;i++)); do
  if curl -fsS "$DEMO_URL/management/health" >/dev/null 2>&1; then
    echo "Demo ready: $DEMO_URL"
    echo 'Seed: supporting_scripts/demo/run-demo.sh seed'
    exit 0
  fi
  if ! kill -0 "$(cat "$STATE/server.pid")" 2>/dev/null; then tail -60 "$STATE/server.log"; exit 1; fi
  sleep 2
done
echo "Server did not become healthy; inspect $STATE/server.log" >&2
exit 1
