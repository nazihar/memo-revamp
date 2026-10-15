#!/usr/bin/env bash
# On-prem Compose profile smoke test (E05 test plan T3): brings up deploy/compose/onprem with the given images and
# generated secrets, then checks: migrate job exited 0 → backend → web; health UP through the web proxy; the backend
# holds no migrator credentials; every long-running container has a read-only root filesystem.
#   ci/onprem-smoke.sh <backend-image> <web-image>
set -euo pipefail
BACKEND_IMAGE=${1:?backend image}
WEB_IMAGE=${2:?web image}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$ROOT/deploy/compose/onprem
PROJECT=memo-smoke-$$
ENV=$(mktemp)
PORT=${SMOKE_PORT:-18080}
dc() { docker compose -p "$PROJECT" -f "$DIR/compose.yaml" --env-file "$ENV" "$@"; }
cleanup() { dc down -v --remove-orphans >/dev/null 2>&1 || true; rm -f "$ENV"; }
trap cleanup EXIT

cp "$DIR/.env.example" "$ENV"
sed -i "s|^MEMO_BACKEND_IMAGE=.*|MEMO_BACKEND_IMAGE=$BACKEND_IMAGE|; s|^MEMO_WEB_IMAGE=.*|MEMO_WEB_IMAGE=$WEB_IMAGE|; s|^MEMO_WEB_PORT=.*|MEMO_WEB_PORT=$PORT|; s|^COMPOSE_PROJECT_NAME=.*|COMPOSE_PROJECT_NAME=$PROJECT|" "$ENV"
sh "$ROOT/deploy/scripts/gen-secrets.sh" "$ENV" >/dev/null
docker network inspect memo-edge >/dev/null 2>&1 || docker network create memo-edge >/dev/null

fail() { echo "on-prem smoke FAILED: $*" >&2; dc ps -a >&2; dc logs --tail 60 migrate backend web >&2; exit 1; }

dc up -d --wait --wait-timeout 300 postgres seaweedfs
dc up -d backend web || fail "stack did not start"

for _ in $(seq 1 90); do
  body=$(curl -s "http://127.0.0.1:$PORT/api/v1/health" || true)
  [[ "$body" == *'"name":"database","status":"UP"'* ]] && break
  sleep 2
done
[[ "${body:-}" == *'"name":"database","status":"UP"'* ]] || fail "health through web: ${body:-<none>}"
[[ "$body" == *'"name":"objectStorage","status":"UP"'* ]] || fail "object storage not UP: $body"

[[ $(docker inspect -f '{{.State.ExitCode}}' "$(dc ps -a -q migrate)") == 0 ]] || fail "migrate job did not exit 0"
if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(dc ps -q backend)" | grep -q '^MEMO_DB_MIGRATOR_PASSWORD=.\+'; then
  fail "backend must not receive migrator credentials"
fi
for svc in backend web postgres seaweedfs; do
  [[ $(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$(dc ps -q "$svc")") == true ]] || fail "$svc root FS is writable"
done
echo "on-prem smoke OK: migrate→backend→web, health UP via proxy, app without migrator credentials, read-only root FS"
