#!/usr/bin/env bash
# CI e2e environment (ADR-0011): Compose *core* stack (PostgreSQL, SeaweedFS, Mailpit), a clamd with the test signature
# database (the `_base` image starts in seconds, as in the backend tests; uploads fail closed without a scanner) and the
# built backend jar on the runner. Playwright then builds/serves the SPA itself (vite preview) and proxies /api to the backend.
# E2E_DOCUMENTS=1 adds ONLYOFFICE (the pinned image, on 8081 where vite proxies /office/) and turns in-browser editing on,
# so the edit journey runs instead of skipping (nightly; ADR-0037). It reaches the backend's internal port on the host.
#   ci/e2e-stack.sh start | logs | stop
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR=${BACKEND_JAR:-$ROOT/backend/build/libs/backend.jar}
LOG=$ROOT/build/e2e-backend.log
SIGNATURES=$ROOT/backend/src/test/resources/files/clamav
OFFICE_IMAGE=onlyoffice/documentserver:10.0.0.1

case "${1:-start}" in
  start)
    node "$ROOT/scripts/dev.mjs" up --core
    eval "$(node "$ROOT/scripts/dev.mjs" env --core | sed 's/^/export /')"
    docker rm -f memo-e2e-clamd >/dev/null 2>&1 || true
    # An argument starting with "-" makes the image's /init run clamd directly, without downloading signatures. The
    # database files are copied in, not mounted: /init changes their owner.
    docker create --name memo-e2e-clamd --env-file "$ROOT/deploy/clamav/clamd.env" -p 127.0.0.1:3310:3310 \
      clamav/clamav:1.5.4_base --foreground >/dev/null
    docker cp "$SIGNATURES/memo-test.hdb" memo-e2e-clamd:/var/lib/clamav/memo-test.hdb
    docker cp "$SIGNATURES/memo-test.ndb" memo-e2e-clamd:/var/lib/clamav/memo-test.ndb
    docker start memo-e2e-clamd >/dev/null
    # The published port accepts connections before clamd listens; ask clamd itself, as the Compose health check does.
    for _ in $(seq 1 60); do
      if docker exec memo-e2e-clamd clamdcheck.sh >/dev/null 2>&1; then break; fi
      sleep 2
    done
    export MEMO_CLAMAV_HOST=localhost MEMO_CLAMAV_PORT=3310
    if [ "${E2E_DOCUMENTS:-}" = 1 ]; then
      # Hex: 64 characters, nothing a shell or the image's config writer could mangle.
      OFFICE_SECRET=$(openssl rand -hex 32)
      docker rm -f memo-e2e-office >/dev/null 2>&1 || true
      docker run -d --name memo-e2e-office -p 127.0.0.1:8081:80 --add-host host.docker.internal:host-gateway \
        -e JWT_ENABLED=true -e JWT_SECRET="$OFFICE_SECRET" -e JWT_HEADER=Authorization -e PLUGINS_ENABLED=false \
        -v "$ROOT/deploy/onlyoffice/local-production-linux.json:/etc/onlyoffice/documentserver/local-production-linux.json:ro" \
        --tmpfs /var/lib/onlyoffice:size=2g,mode=0755 "$OFFICE_IMAGE" >/dev/null
      for _ in $(seq 1 120); do
        if curl -fs http://127.0.0.1:8081/healthcheck | grep -q true; then echo "ONLYOFFICE is up"; break; fi
        sleep 3
      done
      export MEMO_DOCSERVER_URL=http://127.0.0.1:8081 MEMO_DOCEDIT_JWT_SECRET="$OFFICE_SECRET" \
        MEMO_DOCEDIT_INTERNAL_URL=http://host.docker.internal:8082
    fi
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
    docker logs --tail 50 memo-e2e-office 2>&1 || true
    ;;
  stop)
    [ -f "$ROOT/build/e2e-backend.pid" ] && kill "$(cat "$ROOT/build/e2e-backend.pid")" 2>/dev/null || true
    docker rm -f memo-e2e-clamd memo-e2e-office >/dev/null 2>&1 || true
    node "$ROOT/scripts/dev.mjs" down
    ;;
  *) echo "usage: $0 start|logs|stop" >&2; exit 2 ;;
esac
