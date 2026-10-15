# Test plan — E05 Environments, deployment and operability

## Tests per slice
| Slice | Layer | Test (behaviour) | Stage |
|---|---|---|---|
| T1 | Unit | `CorrelationIdFilterTest`: a valid inbound id is kept and echoed; an invalid or oversized id is replaced; MDC is cleared after the request | PR |
| T1 | Integration | `StructuredLoggingIT`: with the `json` format, a request log line is JSON containing `correlation.id` and no secret values | PR |
| T1 | Integration | `ProbesIT`: liveness and readiness UP on the management port; actuator 404 on the main port | PR |
| T2 | Integration | `MigrateModeIT`: migrate mode migrates and exits 0; app mode with Flyway off starts on the migrated schema as `memo_app` | PR |
| T3 | Deployment | `ci/onprem-smoke.sh`: on-prem Compose with the built images (migrate → backend → web) gives health UP through the proxy; all containers non-root and read-only | PR (`images` job) |
| T4 | Deployment | `helm lint` + kubeconform | PR |
| T4 | Deployment | kind install: migration hook completes, backend ready, health through the web service | Nightly |
| T5 | Deployment | `deploy.sh` rollback: a forced smoke failure restores the previous digests (run locally against the Compose profile) | Once + nightly against dev |
| T5 | IaC | `ansible-playbook --syntax-check`, `ansible-lint` | PR |
| T6 | Performance | Gatling `HealthSmokeSimulation` (p95 < 300 ms, errors < 1 %) | Nightly (dev) |
| T6 | Security | ZAP baseline: no high alerts | Nightly (dev) |

## Exit checklist
- [x] All listed tests implemented and green locally (backend 79; on-prem smoke; deploy + rollback; Helm lint/kubeconform; Ansible lint; Gatling; ZAP 0 FAIL/0 WARN). Nightly kind install and dev deployment: **pending push + VM**
- [x] No new quarantined tests
- [x] ASVS checklist updated (V13, V16 rows)
