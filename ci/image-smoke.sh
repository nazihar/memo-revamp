#!/usr/bin/env bash
# Image smoke test (E04 test plan T4): runs the built images the way production does — non-root, read-only root
# filesystem, no capabilities — against a throwaway PostgreSQL, then checks the SPA headers and the /api proxy.
#   ci/image-smoke.sh <backend-image> <web-image>
set -euo pipefail
BACKEND_IMAGE=${1:?backend image}
WEB_IMAGE=${2:?web image}
NET=memo-smoke-$$
PG=memo-smoke-pg-$$
BE=memo-smoke-be-$$
WEB=memo-smoke-web-$$
ROOT=$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))  # pwd -W: native path for docker on Git Bash/Windows
rand() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }
MIGRATOR_PW=$(rand); APP_PW=$(rand); SUPER_PW=$(rand)
DISCARD=$(mktemp)  # not /dev/null: native Windows curl cannot open it

cleanup() { rm -f "$DISCARD"; docker rm -f "$WEB" "$BE" "$PG" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
hardening=(--read-only --tmpfs /tmp:rw,size=64m --cap-drop ALL --security-opt no-new-privileges)

docker network create "$NET" >/dev/null
docker run -d --name "$PG" --network "$NET" \
  -e POSTGRES_PASSWORD="$SUPER_PW" -e POSTGRES_DB=memo \
  -e MEMO_MIGRATOR_PASSWORD="$MIGRATOR_PW" -e MEMO_APP_PASSWORD="$APP_PW" \
  -v "$ROOT/deploy/db/init:/docker-entrypoint-initdb.d:ro" postgres:18.6 >/dev/null
until docker exec "$PG" pg_isready -U postgres -d memo -q; do sleep 1; done
sleep 2 # init scripts run after the first ready signal

docker run -d --name "$BE" --network "$NET" "${hardening[@]}" \
  -e MEMO_DB_URL="jdbc:postgresql://$PG:5432/memo" \
  -e MEMO_DB_APP_PASSWORD="$APP_PW" -e MEMO_DB_MIGRATOR_PASSWORD="$MIGRATOR_PW" \
  -e MEMO_DATA_KEY="$(rand)$(rand)" \
  "$BACKEND_IMAGE" >/dev/null

docker run -d --name "$WEB" --network "$NET" "${hardening[@]}" \
  --tmpfs /etc/nginx/conf.d:rw,uid=101,gid=101 \
  -e BACKEND_URL="http://$BE:8080" -p 127.0.0.1::8080 "$WEB_IMAGE" >/dev/null
PORT=$(docker port "$WEB" 8080/tcp | head -1 | sed 's/.*://')

fail() { echo "image smoke FAILED: $*" >&2; docker logs "$BE" 2>&1 | tail -40 >&2; docker logs "$WEB" 2>&1 | tail -20 >&2; exit 1; }

for _ in $(seq 1 90); do
  body=$(curl -s "http://127.0.0.1:$PORT/api/v1/health" || true)
  [[ "$body" == *'"name":"database","status":"UP"'* ]] && break
  sleep 2
done
[[ "${body:-}" == *'"name":"database","status":"UP"'* ]] || fail "health via web proxy: ${body:-<none>}"

headers=$(curl -s -D - -o "$DISCARD" "http://127.0.0.1:$PORT/")
grep -qi "^content-security-policy: default-src 'self'" <<<"$headers" || fail "missing CSP on /"
grep -qi "^x-frame-options: DENY" <<<"$headers" || fail "missing X-Frame-Options on /"
grep -qi "^cache-control: no-store" <<<"$headers" || fail "index.html must not be cached"
deep=$(curl -s -o "$DISCARD" -w '%{http_code}' "http://127.0.0.1:$PORT/some/client/route")
[[ "$deep" == 200 ]] || fail "SPA fallback returned $deep"

[[ $(docker inspect -f '{{.Config.User}}' "$BACKEND_IMAGE") == 65532:65532 ]] || fail "backend must run as uid 65532"
[[ $(docker inspect -f '{{.Config.User}}' "$WEB_IMAGE") == 101 ]] || fail "web must run as uid 101"
echo "image smoke OK: non-root, read-only root FS, no capabilities; health via proxy UP; SPA headers present"
