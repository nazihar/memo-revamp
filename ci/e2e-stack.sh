#!/usr/bin/env bash
# CI e2e environment (ADR-0011): Compose *core* stack (PostgreSQL, SeaweedFS, Mailpit), a clamd with the test signature
# database (the `_base` image starts in seconds, as in the backend tests; uploads fail closed without a scanner) and the
# built backend jar on the runner. Playwright then builds/serves the SPA itself (vite preview) and proxies /api to the backend.
#   ci/e2e-stack.sh start | logs | stop
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR=${BACKEND_JAR:-$ROOT/backend/build/libs/backend.jar}
LOG=$ROOT/build/e2e-backend.log
SIGNATURES=$ROOT/backend/src/test/resources/files/clamav

case "${1:-start}" in
  start)
    node "$ROOT/scripts/dev.mjs" up --core
    eval "$(node "$ROOT/scripts/dev.mjs" env --core | sed 's/^/export /')"
    docker rm -f memo-e2e-clamd >/dev/null 2>&1 || true
    # An argument starting with "-" makes the image's /init run clamd directly, without downloading signatures.
    docker run -d --name memo-e2e-clamd --env-file "$ROOT/deploy/clamav/clamd.env" -p 127.0.0.1:3310:3310 \
      -v "$SIGNATURES/memo-test.hdb:/var/lib/clamav/memo-test.hdb:ro" \
      -v "$SIGNATURES/memo-test.ndb:/var/lib/clamav/memo-test.ndb:ro" \
      clamav/clamav:1.5.4_base --foreground >/dev/null
    for _ in $(seq 1 60); do
      if (echo >/dev/tcp/127.0.0.1/3310) 2>/dev/null; then break; fi
      sleep 2
    done
    export MEMO_CLAMAV_HOST=localhost MEMO_CLAMAV_PORT=3310
    mkdir -p "$(dirname "$LOG")"
    SPRING_PROFILES_ACTIVE=dev nohup java -jar "$JAR" >"$LOG" 2>&1 &
    echo $! >"$ROOT/build/e2e-backend.pid"
    for _ in $(seq 1 90); do
      if curl -fs http://localhost:8080/api/v1/health | grep -q '"name":"database","status":"UP"'; then
        echo "backend is up"; exit 0
      fi
      sleep 2
    done
    echo "backend did not become healthy" >&2; tail -100 "$LOG" >&2; exit 1
    ;;
  logs)
    [ -f "$LOG" ] && tail -200 "$LOG" || true
    docker logs --tail 50 memo-e2e-clamd 2>&1 || true
    ;;
  stop)
    [ -f "$ROOT/build/e2e-backend.pid" ] && kill "$(cat "$ROOT/build/e2e-backend.pid")" 2>/dev/null || true
    docker rm -f memo-e2e-clamd >/dev/null 2>&1 || true
    node "$ROOT/scripts/dev.mjs" down
    ;;
  *) echo "usage: $0 start|logs|stop" >&2; exit 2 ;;
esac
