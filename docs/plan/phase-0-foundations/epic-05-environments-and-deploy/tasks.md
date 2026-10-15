# E05 Environments, deployment and operability — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | Operability: correlation id filter, ECS JSON logging, OTel starter (export off), Actuator probes on 8081 | M | — |
| T2 | Migration job mode (`memo.mode=migrate`); app instances without Flyway | S | — |
| T3 | Compose on-prem profile + `.env.example` + secret generator; local end-to-end run with images | M | T1, T2 |
| T4 | Helm chart + schema + network policies; lint/kubeconform; kind install | M | T1, T2 |
| T5 | Ansible provisioning + edge proxy (Caddy) + `deploy.sh` with rollback; `deploy-dev.yml` | M | T3 |
| T6 | ZAP baseline + Gatling skeleton (perf project) wired into nightly | S | T3 |
| T7 | `nfr.md`, demo script, retrospective, STATUS | S | all |

## Acceptance criteria
- **T1:**
  - a request with `X-Request-Id: abc12345-…` gets the same id echoed and logged;
  - an invalid id is replaced;
  - JSON log lines contain `correlation.id`;
  - `/actuator/health/readiness` answers on 8081 and not on 8080.
- **T2:**
  - `memo.mode=migrate` migrates an empty DB and exits 0;
  - the app with `spring.flyway.enabled=false` starts against the migrated DB using app-role credentials only.
- **T3:**
  - `docker compose -f deploy/compose/onprem/compose.yaml up` on a clean machine yields health UP through the web proxy;
  - every container is non-root and read-only.
- **T4:** lint and kubeconform are clean; on kind, backend readiness passes and the migration job completes.
- **T5:** `ansible-playbook --syntax-check` and `ansible-lint` are clean; `deploy.sh` rolls back when the smoke check fails.
- **T6:** Gatling smoke runs against the local stack with its assertions met; ZAP baseline runs against the local on-prem stack with no high alerts.
