#!/usr/bin/env bash
# Nightly Helm check on a throwaway kind cluster (E05 test plan T4): installs PostgreSQL (prepared with the real role
# script), creates the referenced Secrets, installs the chart (migration hook → backend → web) and checks health
# through the web Service. Requires: kind, kubectl, helm, and images memo-backend:ci / memo-web:ci built locally.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CLUSTER=${KIND_CLUSTER:-memo-ci}
NS=memo
rand() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }
MIGRATOR_PW=$(rand); APP_PW=$(rand); SUPER_PW=$(rand)

kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s
kind load docker-image memo-backend:ci memo-web:ci --name "$CLUSTER"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$NS" create secret generic memo-db-app --from-literal=password="$APP_PW" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic memo-db-migrator --from-literal=password="$MIGRATOR_PW" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create configmap memo-db-init --from-file="$ROOT/deploy/db/init/01-roles.sh" --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$NS" apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata: { name: postgres }
spec:
  selector: { matchLabels: { app: postgres } }
  template:
    metadata: { labels: { app: postgres } }
    spec:
      containers:
        - name: postgres
          image: postgres:18.6
          env:
            - { name: POSTGRES_DB, value: memo }
            - { name: POSTGRES_PASSWORD, value: "$SUPER_PW" }
            - { name: MEMO_MIGRATOR_PASSWORD, value: "$MIGRATOR_PW" }
            - { name: MEMO_APP_PASSWORD, value: "$APP_PW" }
          ports: [{ containerPort: 5432 }]
          readinessProbe: { exec: { command: [pg_isready, -U, postgres, -d, memo] }, periodSeconds: 3 }
          volumeMounts: [{ name: init, mountPath: /docker-entrypoint-initdb.d }]
      volumes: [{ name: init, configMap: { name: memo-db-init, defaultMode: 0755 } }]
---
apiVersion: v1
kind: Service
metadata: { name: postgres }
spec: { selector: { app: postgres }, ports: [{ port: 5432 }] }
EOF
kubectl -n "$NS" rollout status deploy/postgres --timeout=180s
sleep 5 # init scripts run after the first readiness

helm upgrade --install memo "$ROOT/deploy/helm/memo" -n "$NS" --wait --timeout 10m \
  --set image.backend=memo-backend:ci --set image.web=memo-web:ci --set image.pullPolicy=Never \
  --set database.url=jdbc:postgresql://postgres:5432/memo --set backend.replicas=1 --set web.replicas=1 \
  --set networkPolicy.enabled=false

kubectl -n "$NS" port-forward svc/memo-web 18081:80 >/tmp/pf.log 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  body=$(curl -s http://127.0.0.1:18081/api/v1/health || true)
  [[ "$body" == *'"name":"database","status":"UP"'* ]] && { echo "helm on kind OK: $body"; exit 0; }
  sleep 2
done
kubectl -n "$NS" get pods -o wide; kubectl -n "$NS" logs job/memo-migrate --tail=50 || true
kubectl -n "$NS" logs deploy/memo-backend --tail=80 || true
echo "helm on kind FAILED: ${body:-<none>}" >&2; exit 1
