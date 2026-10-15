# E05 Environments, deployment and operability — threat model (STRIDE)

## Data flows
1. CI → VM over SSH (deploy).
2. Internet → Caddy (TLS) → web → backend.
3. Backend → PostgreSQL / SeaweedFS / ONLYOFFICE / ClamAV / SMTP.
4. Operators → logs and metrics.
5. Kubernetes (customer) → chart.

| # | Threat | Mitigation | ASVS 5.0 | Test / evidence |
|---|---|---|---|---|
| S1 | A stolen CI deploy key is used to run arbitrary commands on the VM | Dedicated `memo` user without sudo; key restricted with `command="/opt/memo/bin/deploy.sh"` in `authorized_keys` (forced command); GitHub environment with approval for staging | V13.x | Ansible role + review |
| T1 | A wrong or unsigned image is deployed | Deploy by digest; in staging, `cosign verify` before deploy once the key exists | V15.1.x | `deploy.sh` checks |
| T2 | Schema migration breaks the running version | Expand/contract; migration job before `up`; rollback to previous digests keeps the newer schema (N-1 compatible) | — | `deploy.sh` rollback test (simulated failure) |
| I1 | Logs leak secrets or personal data | ECS JSON with ids only; Semgrep `memo-no-secrets-in-logs`; correlation id validated (no log injection) | V16.2.x, V16.4.x | `StructuredLoggingIT`, `CorrelationIdFilterTest` |
| I2 | The management endpoints are reachable from the internet | Actuator on 8081, not proxied by nginx or Caddy, and not published in Compose; NetworkPolicy allows only kubelet probes | V13.4.x | `ProbesIT`; Compose and chart review; ZAP baseline |
| I3 | App instances hold DDL rights | Migration job only; app gets app-role credentials | V8.x least privilege | `MigrateModeIT`; chart secrets |
| E1 | Container breakout | read-only, non-root, drop ALL, no-new-privileges, seccomp RuntimeDefault (k8s) | V15.2.x | image smoke; kubeconform + chart policy review |
| E2 | ONLYOFFICE reaches the internet | Internal-only Docker network (Compose); egress NetworkPolicy (k8s) | V15.2.x | Compose config; kind policy check (Phase 3 with ONLYOFFICE content) |
| D1 | DoS on the public health endpoint | Cached health; rate limits in Phase 1a; Caddy request limits | V2.4.x | Gatling smoke |
| R1 | Unknown who deployed what | `deploy.sh` appends to `/opt/memo/<env>/deployments.log` (time, digests, CI run URL) | V16.x | review |

## Residual risks
- Dev and staging share a VM, so a compromise of one affects both. Accepted for non-production; production is SaaS or on-prem per customer.
- ACME uses the public Let's Encrypt service for dev/staging TLS. This is not a product runtime dependency; on-prem customers use their own certificates.
