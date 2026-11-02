#!/usr/bin/env bash
# OWASP ZAP baseline (passive) scan (TESTING_STRATEGY §6): fails on any rule set to FAIL in ci/zap/rules.tsv or any
# new high-risk alert; IGNOREs need a reason in that file. Reports land in build/zap/<host>/, one folder per target:
# a scan cannot reuse the folder of an earlier one, whose files belong to the scanner container user.
#   ci/zap-baseline.sh <url>        (a localhost URL is rewritten to host.docker.internal for the scanner container)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TARGET=${1:?target url}
TARGET=${TARGET/localhost/host.docker.internal}
TARGET=${TARGET/127.0.0.1/host.docker.internal}
HOST=${TARGET#*://}
OUT=$ROOT/build/zap/$(printf %s "${HOST%%/*}" | tr -c 'A-Za-z0-9.-' _)
mkdir -p "$OUT" && cp "$ROOT/ci/zap/rules.tsv" "$OUT/rules.tsv" && chmod -R a+rwX "$OUT"
docker run --rm --add-host=host.docker.internal:host-gateway -v "$OUT:/zap/wrk:rw" zaproxy/zap-stable:2.17.0 \
  zap-baseline.py -t "$TARGET" -c rules.tsv -J zap.json -r zap.html
