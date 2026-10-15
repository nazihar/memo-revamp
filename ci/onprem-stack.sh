#!/usr/bin/env bash
# Starts/stops the on-prem Compose profile with given images and generated secrets, for nightly DAST/perf runs.
#   ci/onprem-stack.sh up <backend-image> <web-image> [port]   → web on http://127.0.0.1:<port> (default 18100)
#   ci/onprem-stack.sh down
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$ROOT/deploy/compose/onprem
STATE=$ROOT/build/onprem-stack
dc() { docker compose -p memo-nightly -f "$DIR/compose.yaml" --env-file "$STATE/.env" "$@"; }

case "${1:-}" in
  up)
    BACKEND=${2:?backend image}; WEB=${3:?web image}; PORT=${4:-18100}
    mkdir -p "$STATE" && cp "$DIR/.env.example" "$STATE/.env"
    sed -i "s|^MEMO_BACKEND_IMAGE=.*|MEMO_BACKEND_IMAGE=$BACKEND|; s|^MEMO_WEB_IMAGE=.*|MEMO_WEB_IMAGE=$WEB|; s|^MEMO_WEB_PORT=.*|MEMO_WEB_PORT=$PORT|; s|^MEMO_WEB_BIND=.*|MEMO_WEB_BIND=0.0.0.0|; s|^COMPOSE_PROJECT_NAME=.*|COMPOSE_PROJECT_NAME=memo-nightly|; s|^MEMO_DEMO_SEED=.*|MEMO_DEMO_SEED=true|; s|^MEMO_SPRING_PROFILES=.*|MEMO_SPRING_PROFILES=ci|" "$STATE/.env"
    sh "$ROOT/deploy/scripts/gen-secrets.sh" "$STATE/.env" >/dev/null
    docker network inspect memo-edge >/dev/null 2>&1 || docker network create memo-edge >/dev/null
    dc up -d --wait --wait-timeout 300 postgres seaweedfs
    dc up -d
    for _ in $(seq 1 90); do
      curl -fs "http://127.0.0.1:$PORT/api/v1/health" | grep -q '"name":"database","status":"UP"' && { echo "stack up on :$PORT"; exit 0; }
      sleep 2
    done
    dc logs --tail 80 migrate backend web >&2; exit 1
    ;;
  down) [ -f "$STATE/.env" ] && dc down -v --remove-orphans; rm -rf "$STATE" ;;
  *) echo "usage: $0 up <backend> <web> [port] | down" >&2; exit 2 ;;
esac
