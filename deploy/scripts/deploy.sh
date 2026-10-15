#!/usr/bin/env bash
# Deploys one environment on the VM with the on-prem Compose profile (ADR-0015), with automatic rollback.
#
#   deploy.sh <env> <backend-image@sha256:...> <web-image@sha256:...> [manifests.tgz]
#
# 1. unpack the release's manifests (deploy/ tree) next to the previous ones
# 2. point .env at the new image digests (previous values kept for rollback)
# 3. pull; `up -d` runs the one-shot `migrate` job before the backend (depends_on), then web
# 4. wait for /api/v1/health through the web container's published port; on failure restore manifests + digests
# 5. append to deployments.log (time, digests, CI run)
#
# Environment: MEMO_BASE (default /opt/memo), REQUIRE_DIGEST (default true), MEMO_CI_RUN (CI run URL, optional).
set -euo pipefail
ENV_NAME=${1:?env (dev|staging)}
BACKEND=${2:?backend image@digest}
WEB=${3:?web image@digest}
BUNDLE=${4:-}
BASE=${MEMO_BASE:-/opt/memo}
DIR=$BASE/$ENV_NAME
REQUIRE_DIGEST=${REQUIRE_DIGEST:-true}

[[ "$ENV_NAME" =~ ^(dev|staging)$ ]] || { echo "unknown environment: $ENV_NAME" >&2; exit 2; }
if [[ "$REQUIRE_DIGEST" == true ]]; then
  for ref in "$BACKEND" "$WEB"; do
    [[ "$ref" =~ ^[a-z0-9./_-]+(:[A-Za-z0-9._-]+)?@sha256:[0-9a-f]{64}$ ]] || { echo "not a digest-pinned image: $ref" >&2; exit 2; }
  done
fi
[ -f "$DIR/.env" ] || { echo "$DIR/.env missing: provision the VM first (deploy/ansible)" >&2; exit 2; }

# One deployment per environment at a time (flock is part of util-linux on the VM).
exec 9>"$DIR/.deploy.lock"
if command -v flock >/dev/null 2>&1; then
  flock -n 9 || { echo "another deployment of $ENV_NAME is running" >&2; exit 3; }
fi
# Distinct project per environment: container names, volumes and the edge alias memo-<env>-web.
export COMPOSE_PROJECT_NAME=memo-$ENV_NAME

# --- 1. manifests ---
if [ -n "$BUNDLE" ]; then
  rm -rf "$DIR/manifests.new" && mkdir -p "$DIR/manifests.new"
  tar -xzf "$BUNDLE" -C "$DIR/manifests.new"
  rm -rf "$DIR/manifests.prev"
  [ -d "$DIR/manifests" ] && mv "$DIR/manifests" "$DIR/manifests.prev"
  mv "$DIR/manifests.new" "$DIR/manifests"
fi
COMPOSE_FILE=$DIR/manifests/deploy/compose/onprem/compose.yaml
dc() { docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" --env-file "$DIR/.env" "$@"; }

# --- 2. digests ---
get() { grep -E "^$1=" "$DIR/.env" | cut -d= -f2- || true; }
set_var() { sed -i "s|^$1=.*|$1=$2|" "$DIR/.env"; }
PREV_BACKEND=$(get MEMO_BACKEND_IMAGE)
PREV_WEB=$(get MEMO_WEB_IMAGE)
set_var MEMO_BACKEND_IMAGE "$BACKEND"
set_var MEMO_WEB_IMAGE "$WEB"
PORT=$(get MEMO_WEB_PORT); PORT=${PORT:-8080}

healthy() {
  for _ in $(seq 1 ${HEALTH_ATTEMPTS:-90}); do
    if curl -fs "http://127.0.0.1:$PORT/api/v1/health" | grep -q '"name":"database","status":"UP"'; then return 0; fi
    sleep 2
  done
  return 1
}

rollback() {
  echo "deployment failed: rolling back $ENV_NAME" >&2
  dc logs --tail 80 migrate backend web >&2 || true
  set_var MEMO_BACKEND_IMAGE "$PREV_BACKEND"
  set_var MEMO_WEB_IMAGE "$PREV_WEB"
  if [ -d "$DIR/manifests.prev" ]; then
    rm -rf "$DIR/manifests.failed" && mv "$DIR/manifests" "$DIR/manifests.failed" && mv "$DIR/manifests.prev" "$DIR/manifests"
  fi
  # The newer schema stays: migrations are expand/contract, so the previous version runs on it (AGENTS.md §4).
  if [[ "$PREV_BACKEND" != *REPLACE* && -n "$PREV_BACKEND" ]]; then
    dc up -d --remove-orphans backend web && healthy && echo "rolled back to $PREV_BACKEND / $PREV_WEB" >&2
  fi
  echo "$(date -u +%FT%TZ) $ENV_NAME FAILED backend=$BACKEND web=$WEB rolled-back-to=$PREV_BACKEND ${MEMO_CI_RUN:-}" >>"$DIR/deployments.log"
  exit 1
}

# --- 3/4. pull, migrate, start, verify ---
if [ "${SKIP_PULL:-false}" != true ]; then dc pull --quiet backend web migrate || rollback; fi
dc up -d --remove-orphans || rollback
healthy || rollback

echo "$(date -u +%FT%TZ) $ENV_NAME OK backend=$BACKEND web=$WEB ${MEMO_CI_RUN:-}" >>"$DIR/deployments.log"
echo "deployed $ENV_NAME: backend=$BACKEND web=$WEB"
