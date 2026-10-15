#!/usr/bin/env bash
# CI e2e environment (ADR-0011): Compose *core* stack (PostgreSQL, SeaweedFS, Mailpit) + the built backend jar on the
# runner. Playwright then builds/serves the SPA itself (vite preview) and proxies /api to the backend.
#   ci/e2e-stack.sh start | logs | stop
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR=${BACKEND_JAR:-$ROOT/backend/build/libs/backend.jar}
LOG=$ROOT/build/e2e-backend.log

case "${1:-start}" in
  start)
    node "$ROOT/scripts/dev.mjs" up --core
    eval "$(node "$ROOT/scripts/dev.mjs" env --core | sed 's/^/export /')"
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
    ;;
  stop)
    [ -f "$ROOT/build/e2e-backend.pid" ] && kill "$(cat "$ROOT/build/e2e-backend.pid")" 2>/dev/null || true
    node "$ROOT/scripts/dev.mjs" down
    ;;
  *) echo "usage: $0 start|logs|stop" >&2; exit 2 ;;
esac
