#!/usr/bin/env bash
# OWASP ZAP baseline (passive) scan (TESTING_STRATEGY §6): fails on any rule set to FAIL in ci/zap/rules.tsv or any
# new high-risk alert; IGNOREs need a reason in that file. Reports land in build/zap/.
#   ci/zap-baseline.sh <url>        (a localhost URL is rewritten to host.docker.internal for the scanner container)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TARGET=${1:?target url}
TARGET=${TARGET/localhost/host.docker.internal}
TARGET=${TARGET/127.0.0.1/host.docker.internal}
OUT=$ROOT/build/zap
mkdir -p "$OUT" && cp "$ROOT/ci/zap/rules.tsv" "$OUT/rules.tsv" && chmod -R a+rwX "$OUT"
docker run --rm --add-host=host.docker.internal:host-gateway -v "$OUT:/zap/wrk:rw" zaproxy/zap-stable:2.17.0 \
  zap-baseline.py -t "$TARGET" -c rules.tsv -J zap.json -r zap.html
